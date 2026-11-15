package dev.lull.optsupport;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.gpu.uploadRing=true (11-15, the N100's 350-ms stalls): per-frame updates of buffers the GPU still reads go
 * through one persistent, coherently mapped staging buffer made once - the bytes are copied into it on the CPU and
 * glCopyBufferSubData moves them on the GPU, in command order. glBufferSubData into a busy buffer made the Intel driver
 * create a staging allocation of exactly that size each time (0.4-2 MB, ~20 MB a second in the horde scene, its own
 * worker thread), and each allocation made / destroyed waited for Windows' video memory manager (dxgmms2) - normally
 * 1-3 ms, in the bad runs ~350 ms on the render thread. With the ring nothing is allocated after the start.
 *
 * <p>The ring has NSEG segments, one per frame; a fence after a frame's copies guards its segment. A segment the GPU has
 * not finished with, or a copy that does not fit, falls back to the caller's own path - no waiting, ever. Render
 * thread only. Needs GL 4.4 (or ARB_buffer_storage); else always the fallback.
 */
public final class UploadRing {
    private UploadRing() {}

    public static final boolean ON = Boolean.getBoolean("lab.gpu.uploadRing");
    /** segments: more than the frames the driver keeps in flight (N100 at the GPU limit: 4 were too few - 80 % busy) */
    private static final int NSEG = Integer.getInteger("lab.gpu.uploadRing.segments", 8);
    private static final long SEG = Long.getLong("lab.gpu.uploadRing.segMiB", 8) << 20;
    private static final long ALIGN = 256;
    /** uploads smaller than this keep the driver's own path (GrowBuffer): the ring is for the large ones that made allocations */
    public static final long MIN_BYTES = Long.getLong("lab.gpu.uploadRing.minKiB", 64) << 10;
    private static int buffer;
    private static long ptr;
    private static int seg;
    private static long used;
    private static boolean segFree = true;
    private static final long[] FENCES = new long[NSEG];
    private static Boolean capable;
    public static long copies, bytes, fallbackBusy, fallbackFull;
    /** off: every copy falls back (the same storage, glBufferSubData) - ClientAuto's in-frame A/B flips it */
    public static boolean enabled = true;

    private static boolean capable() {
        if (capable == null) {
            GLCapabilities c = GL.getCapabilities();
            capable = c.OpenGL44 || c.GL_ARB_buffer_storage;
        }
        return capable;
    }

    /**
     * -Dlab.gpu.framesInFlight=K (K > 0): at a frame's start the render thread waits (an OS wait on a fence, up to 100 ms)
     * until the GPU has finished the frame K frames back. The N100's Intel driver lets the CPU run far ahead of the GPU
     * (the ring's segments were still busy 8 frames later): K bounds that queue - the input reaches the screen sooner and
     * the ring's NSEG > K segments are always free. Works with the ring off too (its fences are made either way).
     */
    private static final int FIF_FIXED = Integer.getInteger("lab.gpu.framesInFlight", 0);
    /**
     * -Dlab.gpu.framesInFlight.auto=true: the limit (K = 3) only where the driver queues frames deeper than a sane driver
     * does - the frame DEEP frames back still not done at a frame's start in a quarter of a window's frames (the N100's
     * Intel driver: 8 and more; NVIDIA keeps at most 3 queued and blocks in the swap itself). Once seen it stays on and is
     * remembered for the machine (Calibration "fif"); the host's 4.5 % cost of the wait is not paid where it is not needed.
     */
    private static final boolean AUTO = FIF_FIXED == 0 && Boolean.getBoolean("lab.gpu.framesInFlight.auto");
    private static final int FIF = FIF_FIXED > 0 ? FIF_FIXED : AUTO ? 3 : 0;
    private static final int DEEP = Integer.getInteger("lab.gpu.framesInFlight.deep", 6), AUTO_WINDOW = 240;
    private static final long[] FRAME_FENCES = new long[16];
    /** per FRAME_FENCES slot: the frame count (frames) when the fence was made - it covers that frame's commands */
    private static final long[] FENCE_AT = new long[16];

    /**
     * the fence made at the start of the frame after the one counted {@code at} (every command of frame {@code at},
     * its atlas ticks included, before it), or 0 if not made (yet) or gone
     */
    public static long fenceAfter(long at) {
        for (int i = 0; i < 16; i++)
            if (FRAME_FENCES[i] != 0 && FENCE_AT[i] == at) return FRAME_FENCES[i];
        return 0;
    }
    private static int frameNo;
    public static long fifWaits, fifWaitNs, frames, deepFrames;
    /** off: no waiting (the fences still made) - ClientAuto's in-window flip switches it */
    public static boolean fifEnabled = true;
    /**
     * test (flippable): the limit at -Dlab.gpu.framesInFlight.deepK (6) frames instead of K - a deeper queue hides a long
     * client tick from the GPU (11-15 N100: limit off 0.14 % GPU stutters, K = 3 5.67 %), at the cost of latency
     */
    public static boolean fifDeep = Boolean.getBoolean("lab.gpu.framesInFlight.deep6");
    private static final int DEEP_K = Math.min(15, Integer.getInteger("lab.gpu.framesInFlight.deepK", 6));
    /** the limit applies: fixed by the flag, or the automatic one decided (see AUTO) */
    private static boolean limiting = FIF_FIXED > 0;

    /** the limit applies now (fixed or found needed): no frame more than FIF frames ahead of the GPU */
    public static boolean limitingNow() {
        return FIF > 0 && limiting && fifEnabled;
    }
    private static boolean autoChecked;
    private static int autoFrames, autoDeep;
    public static String fifWhy = FIF_FIXED > 0 ? "fixed by the flag" : AUTO ? "watching the queue" : "off";

    /** a look at a fence (no flush, no wait): glGetSynci(GL_SYNC_STATUS) cost the host's NVIDIA driver 45 % of its frames */
    /** per measured frame: the frames still queued at its start (the frame log's queue column; -1 none) */
    public static final byte[] DEPTH = new byte[1 << 16];

    static {
        java.util.Arrays.fill(DEPTH, (byte) -1);
    }

    private static boolean done(long fence) {
        int r = GL32C.glClientWaitSync(fence, 0, 0);
        return r == GL32C.GL_ALREADY_SIGNALED || r == GL32C.GL_CONDITION_SATISFIED;
    }

    /** windows in a row with a normal queue: after QUIET_WINDOWS the watch ends (no fences any more) */
    private static int quietWindows;
    private static boolean watching = true;
    private static final int QUIET_WINDOWS = 30;

    /** the automatic limit: a look at the frame DEEP back (never a wait) */
    private static void watchQueue() {
        if (!autoChecked) {
            autoChecked = true;
            if ("true".equals(Calibration.get("fif"))) {
                limiting = true;
                fifWhy = "on (calibrated earlier: " + Calibration.get("fif.why") + ")";
                System.out.println("[sleep/opt] frames in flight: " + fifWhy);
                return;
            }
        }
        if (frameNo < DEEP || (frameNo & 1) != 0) return;   // (every other frame: a sample)
        long f = FRAME_FENCES[(frameNo - DEEP) & 15];
        if (f == 0) return;
        boolean deep = !done(f);
        if (deep) {
            autoDeep++;
            deepFrames++;
        }
        if (++autoFrames < AUTO_WINDOW) return;
        if (autoDeep * 4 >= autoFrames) {
            quietWindows = 0;
            limiting = true;
            fifWhy = String.format(java.util.Locale.ROOT, "on: the frame %d back not done at %d of %d frames' starts", DEEP, autoDeep, autoFrames);
            System.out.println("[sleep/opt] frames in flight: " + fifWhy);
            Calibration.put("fif", "true", fifWhy);
        } else if (autoDeep == 0 && ++quietWindows >= QUIET_WINDOWS) {
            watching = false;   // a driver that keeps a short queue: nothing more to watch (fences stop)
            fifWhy = "off: the queue stayed short for " + QUIET_WINDOWS + " windows";
            System.out.println("[sleep/opt] frames in flight: " + fifWhy);
        }
        autoFrames = 0;
        autoDeep = 0;
    }

    /** Minecraft.runTick HEAD (UploadRingMixin): the last frame gets its fence, the next segment is taken if the GPU is done with it */
    public static void frameBegin() {
        if (FIF > 0 && (limiting || watching)) {
            // a fence for every frame, the oldest one we keep waited for: at most FIF frames queued ahead of the GPU
            int i = frameNo & 15;
            if (FRAME_FENCES[i] != 0) GL32C.glDeleteSync(FRAME_FENCES[i]);
            FRAME_FENCES[i] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            FENCE_AT[i] = frames;
            if (!limiting) watchQueue();
            if (dev.lull.ClientMeter.measuring()) {   // the queue: frames sent whose fence is not done (looks without the flush bit)
                int d = 0;
                for (int b = 1; b < 16 && b <= frameNo; b++) {
                    long fb = FRAME_FENCES[(frameNo - b) & 15];
                    if (fb == 0 || done(fb)) break;
                    d++;
                }
                int fi = dev.lull.ClientMeter.frameIndex();
                if (fi >= 0 && fi < DEPTH.length) DEPTH[fi] = (byte) d;
            }
            int k = fifDeep ? DEEP_K : FIF;
            long old = limiting && fifEnabled && frameNo >= k ? FRAME_FENCES[(frameNo - k) & 15] : 0;
            if (old != 0) {
                int r = GL32C.glClientWaitSync(old, 0, 0);   // a look without the flush bit (it made the Intel driver submit every frame; the fence K frames back went with a swap long ago)
                if (r == GL32C.GL_TIMEOUT_EXPIRED) {
                    long t0 = System.nanoTime();
                    GL32C.glClientWaitSync(old, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
                    fifWaits++;
                    fifWaitNs += System.nanoTime() - t0;
                }
            }
            frameNo++;
        }
        frames++;
        if (buffer == 0) return;
        if (used > 0) FENCES[seg] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        seg = (seg + 1) % NSEG;
        used = 0;
        long f = FENCES[seg];
        if (f != 0) {
            int r = GL32C.glClientWaitSync(f, 0, 0);   // a look, never a wait (no flush bit: the fence went with an earlier swap)
            segFree = r == GL32C.GL_ALREADY_SIGNALED || r == GL32C.GL_CONDITION_SATISFIED;
            if (segFree) {
                GL32C.glDeleteSync(f);
                FENCES[seg] = 0;
            }
        } else {
            segFree = true;
        }
    }

    /**
     * {@code n} bytes at {@code src} into buffer {@code dst} at {@code dstOffset}, through the ring. False: not taken
     * (off, no GL 4.4, the frame's segment busy or full) - the caller uploads its own way. Leaves COPY_READ / COPY_WRITE bound.
     */
    /**
     * -Dlab.gpu.ringRecords=true: PartBatch's sorted part records written straight into the ring and read by the shader
     * from there (glTexBufferRange) - no staging memory, no upload, no storage re-specified each frame.
     */
    public static final boolean RECORDS = Boolean.getBoolean("lab.gpu.ringRecords");
    public static long recordFrames, recordBytes, recordFallbacks;

    /**
     * {@code n} bytes of this frame's segment, at an offset aligned to {@code align}: the offset in buffer(), or -1 (busy,
     * full, no GL 4.4) - the caller writes the bytes at address(offset) before issuing the draw that reads them.
     */
    public static long alloc(long n, long align) {
        if (!enabled || n <= 0 || !capable()) return -1;
        ensure();
        long at = (used + align - 1) / align * align;
        if (!segFree || at + n > SEG) {
            recordFallbacks++;
            return -1;
        }
        used = at + n;
        recordBytes += n;
        return seg * SEG + at;
    }

    public static long address(long offset) {
        return ptr + offset;
    }

    public static int buffer() {
        return buffer;
    }

    private static void ensure() {
        if (buffer == 0) {
            buffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
            int flags = GL44C.GL_MAP_WRITE_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;
            GL44C.glBufferStorage(GL31C.GL_COPY_READ_BUFFER, SEG * NSEG, flags);
            ptr = MemoryUtil.memAddress(GL44C.glMapBufferRange(GL31C.GL_COPY_READ_BUFFER, 0, SEG * NSEG, flags));
            seg = 0;
            used = 0;
            segFree = true;
            System.out.println("[sleep/opt] upload ring: " + NSEG + " x " + (SEG >> 20) + " MiB, persistent coherent mapping");
        }
    }

    public static boolean copy(int dst, long dstOffset, long src, long n) {
        if (!ON || !enabled || n <= 0 || dst == 0 || !capable()) return false;
        if (buffer == 0) {
            buffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
            int flags = GL44C.GL_MAP_WRITE_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;
            GL44C.glBufferStorage(GL31C.GL_COPY_READ_BUFFER, SEG * NSEG, flags);
            ptr = MemoryUtil.memAddress(GL44C.glMapBufferRange(GL31C.GL_COPY_READ_BUFFER, 0, SEG * NSEG, flags));
            seg = 0;
            used = 0;
            segFree = true;
            System.out.println("[sleep/opt] upload ring: " + NSEG + " x " + (SEG >> 20) + " MiB, persistent coherent mapping");
        }
        if (!segFree) {
            fallbackBusy++;
            return false;
        }
        if (used + n > SEG) {
            fallbackFull++;
            return false;
        }
        long off = seg * SEG + used;
        MemoryUtil.memCopy(src, ptr + off, n);
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, dst);
        GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, off, dstOffset, n);
        used += (n + ALIGN - 1) & -ALIGN;
        copies++;
        bytes += n;
        return true;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "upload ring: %d copies, %.1f MB; fallbacks: segment busy %d, full %d; records in the ring %.1f MB (fallbacks %d); frames %d, frames-in-flight %d (%s; deep frames %d): waited %d times, %.2f ms a frame",
                copies, bytes / 1e6, fallbackBusy, fallbackFull, recordBytes / 1e6, recordFallbacks, frames, FIF, fifWhy, deepFrames, fifWaits, frames == 0 ? 0 : fifWaitNs / 1e6 / frames);
    }
}
