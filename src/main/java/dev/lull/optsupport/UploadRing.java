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
    private static final int NSEG = 4;
    private static final long SEG = Long.getLong("lab.gpu.uploadRing.segMiB", 4) << 20;
    private static final long ALIGN = 256;
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

    /** Minecraft.runTick HEAD (UploadRingMixin): the last frame's segment gets its fence, the next one is taken if the GPU is done with it */
    public static void frameBegin() {
        if (buffer == 0) return;
        if (used > 0) FENCES[seg] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        seg = (seg + 1) % NSEG;
        used = 0;
        long f = FENCES[seg];
        if (f != 0) {
            int r = GL32C.glClientWaitSync(f, 0, 0);   // a look, never a wait
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
        return String.format(java.util.Locale.ROOT, "upload ring: %d copies, %.1f MB; fallbacks: segment busy %d, full %d",
                copies, bytes / 1e6, fallbackBusy, fallbackFull);
    }
}
