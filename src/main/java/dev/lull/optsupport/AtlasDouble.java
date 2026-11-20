package dev.lull.optsupport;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL43C;

/**
 * Lab (11-20, -Dlab.atlasDouble=true with GpuAnim): an animated atlas kept as two GL textures, front (drawn) and back.
 * An animation tick writes into the back one - the copy the GPU is not reading - after bringing it up to date with the
 * previous tick's changes (copied from the front: reading a texture in use does not stall, writing into it does), then
 * the two swap: the atlas's id is the back one from the next frame on. A tiled GPU (the Pi's V3D, phones' Adreno /
 * Mali) waited for its previous frame on every write into the atlas it was reading: on the Pi ~10 ms an atlas tick,
 * ~28 ms of a 34 ms client tick; frozen animations: 23 -> 27 fps, p99 80 -> 55 ms; the phone's p99 -25 %. The pixels
 * drawn are the same: the front holds exactly what the single atlas would.
 */
public final class AtlasDouble {
    private AtlasDouble() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final boolean ON = Boolean.getBoolean("lab.atlasDouble");

    /** -Dlab.atlasDouble.copies=N (default 2): N textures in rotation - the one written was drawn N-1 ticks ago */
    /** -Dlab.atlasDouble.paramsEveryTick: copy the filter parameters to the next copy every tick (glGet: a wait on Mesa) */
    static final boolean PARAMS_EVERY_TICK = Boolean.getBoolean("lab.atlasDouble.paramsEveryTick");
    static final int COPIES = Math.max(2, Integer.getInteger("lab.atlasDouble.copies", 2));

    private static final class State {
        int w, h, levels;
        final java.util.ArrayDeque<Integer> backs = new java.util.ArrayDeque<>();   // not drawn now, oldest first
        final java.util.ArrayDeque<int[]> history = new java.util.ArrayDeque<>();   // the last N-1 ticks' rects (x, y, w, h ...; [0] = length)
        int[] cur = new int[65];
        int nCur;
        int target;
        /** REPLAY: the last N-1 ticks' writes as operations (oldest first), this tick's, spare ones */
        final java.util.ArrayDeque<Ops> opsHistory = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Ops> freeOps = new java.util.ArrayDeque<>();
        Ops curOps;
    }

    /**
     * One tick's writes into the atlas, to be done again in a back copy: a copy from a sprite sheet (static, a texture of
     * its own), an upload of pixels (kept here: the interpolated frame's image is overwritten next tick), or a rect only
     * (another path wrote it: copied from the front). Records of 9 ints: kind, x, y, w, h, levels, a, b, c.
     */
    static final class Ops {
        static final int RECT = 0, COPY = 1, UPLOAD = 2;
        int[] op = new int[9 * 32];
        int n;
        java.nio.IntBuffer px = org.lwjgl.system.MemoryUtil.memAllocInt(4096);   // native memory (the Pi: DRAM, reused)
        int pxN;

        void add(int kind, int x, int y, int w, int h, int levels, int a, int b, int c) {
            if (n + 9 > op.length) op = java.util.Arrays.copyOf(op, op.length * 2);
            op[n] = kind; op[n + 1] = x; op[n + 2] = y; op[n + 3] = w; op[n + 4] = h; op[n + 5] = levels; op[n + 6] = a; op[n + 7] = b; op[n + 8] = c;
            n += 9;
        }

        void ensurePx(int more) {
            if (pxN + more > px.capacity()) px = org.lwjgl.system.MemoryUtil.memRealloc(px, Math.max(px.capacity() * 2, pxN + more));
        }
    }

    /** -Dlab.atlasDouble.replay: the catch-up does the previous ticks' writes again from their sources (sheets, kept
     *  pixels) - never a read of the front, the texture the GPU is drawing with (on V3D a copy out of it cost ~9 ms a tick) */
    static final boolean REPLAY = !"false".equals(System.getProperty("lab.atlasDouble.replay"));   // (default since 11-21: the rects lose everywhere)
    static boolean replayNow;

    /** -Dlab.atlasDouble.whole: the catch-up copies whole levels (one call a level) instead of the changed rects */
    static final boolean WHOLE = Boolean.getBoolean("lab.atlasDouble.whole");
    /** -Dlab.atlasDouble.blit: the catch-up copies drawn by framebuffer blits (the GPU copies, no CPU map on V3D) */
    static final boolean BLIT = Boolean.getBoolean("lab.atlasDouble.blit");
    /** -Dlab.atlasDouble.blitLate: the blits at the tick's end, after its own (CPU on V3D) writes into the back - those
     *  would otherwise wait for the blit job; rects this tick rewrote are skipped (its pixels are the newer ones) */
    static final boolean BLIT_LATE = Boolean.getBoolean("lab.atlasDouble.blitLate");

    // ------------------------------------------------------------------ measured per machine (11-21)

    /**
     * -Dlab.atlasDouble.way=tune (default) / on / off. Measured by whole client ticks (Minecraft.tick, the atlas ticks
     * and every wait they cause inside) in windows A B B A (A one atlas, B the copies; 100 ticks each, warm-ups not counted): the means
     * (A1 + A2) / 2 against (B1 + B2) / 2 cancel a steady drift - the first version (A then B) chose the copies on the
     * N100 from a warming-up first window (1.9 against 0.9 ms; its steady ticks are 1.1 against 1.6). The copies kept for
     * a 10 % shorter tick; the choice kept in the calibration file. 11-21, the Pi's steady scene: replay 4.9 ms against
     * 9.5 (client tick 9.5 against 34 ms, 26 against 23 fps); the Radeon boxes 7.1 against 3.7 (client tick 13 against
     * 6.3 ms) - the copies only where writing the drawn atlas waits.
     */
    static final String WAY = System.getProperty("lab.atlasDouble.way", "tune");
    /** 0 measuring (the steps below), 4 on, 5 off */
    private static int phase = "on".equals(WAY) ? 4 : "off".equals(WAY) ? 5 : 0;
    /** the measuring steps: copies used, ticks, window slot (-1: not counted) */
    private static final boolean[] STEP_COPIES = {false, false, true, true, true, false, false};
    private static final int[] STEP_TICKS = {20, 100, 20, 100, 100, 5, 100};
    private static final int[] STEP_SLOT = {-1, 0, -1, 1, 2, -1, 3};
    private static final int WINDOW = 100;
    private static final Samples TUNE = new Samples(WINDOW);
    private static final double[] SLOT = new double[4];
    private static int step, tuneTicks;
    private static Object tuneLevel;
    private static long levelSince;
    private static final long DELAY_NS = Long.getLong("lab.atlasDouble.delaySeconds", 60) * 1_000_000_000L;
    private static boolean calibChecked;
    public static String choice = "not decided";

    /** the copies are used in this atlas tick */
    public static boolean use() {
        // (1.21.1: not with the GL worker - its frame copies run later on another thread, unrecorded here)
        return ON && !GlWorker.ON && !GpuAnim.blendsMayDraw() && (phase == 4 || phase == 0 && STEP_COPIES[step]);
    }

    /** an atlas tick is done (GpuAnimAtlasMixin, after the swap) */
    public static void tickDone(long ns) {
        if (!ON) return;
        // GpuAnim's GPU blends (its measuring windows, or chosen): no copies - 11-21 the host's frame hash differed with
        // the copies on while the blends were measured (c94c63bd against 5a6d291d); not proven exact with them
        if (GpuAnim.blendsMayDraw() && !STATES.isEmpty()) dropAll();
    }

    /**
     * A client tick is done (AtlasDoubleTickMixin: Minecraft.tick's whole time): the measuring. By client ticks, not
     * atlas ticks: 11-21 on the Pi the atlas tick alone chose wrongly twice (one atlas 3.1 / 4.5-6.5 ms in the windows
     * against ~10 later) while the whole tick is where the waits land (34 against 9.5 ms).
     */
    public static void clientTick(long ns) {
        if (!ON || phase >= 4 || GpuAnim.blendsMayDraw()) return;
        if (!calibChecked) {
            calibChecked = true;
            String c = Calibration.get("atlas2");
            if ("on".equals(c) || "off".equals(c)) {
                phase = "on".equals(c) ? 4 : 5;
                choice = c + " (calibrated earlier: " + Calibration.get("atlas2.why") + ")";
                LOG.info("[sleep/opt] atlas double: {}", choice);
                return;
            }
        }
        if (GpuAnim.measuring()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.screen != null) return;   // in a world, nothing over it
        // not in a world's first minute: 11-21 the Pi measured little difference there (the GPU not yet loaded)
        if (mc.level != tuneLevel) {
            tuneLevel = mc.level;
            levelSince = System.nanoTime();
        }
        if (System.nanoTime() - levelSince < DELAY_NS) return;
        if (STEP_SLOT[step] >= 0) TUNE.add(ns);
        if (++tuneTicks < STEP_TICKS[step]) return;
        if (STEP_SLOT[step] >= 0) SLOT[STEP_SLOT[step]] = TUNE.trimmed();
        TUNE.clear();
        tuneTicks = 0;
        boolean wasCopies = STEP_COPIES[step];
        if (++step < STEP_TICKS.length) {
            if (wasCopies && !STEP_COPIES[step]) dropAll();   // back to one atlas: the copies freed (made again if used)
            return;
        }
        step = 0;
        double one = (SLOT[0] + SLOT[3]) / 2, two = (SLOT[1] + SLOT[2]) / 2;
        boolean on = two < one * 0.9;   // (a tie: the one atlas, vanilla's way)
        phase = on ? 4 : 5;
        choice = String.format(java.util.Locale.ROOT, "%s (a client tick %.3f ms with the copies [%.3f, %.3f], %.3f with one atlas [%.3f, %.3f])",
                on ? "on" : "off", two / 1e6, SLOT[1] / 1e6, SLOT[2] / 1e6, one / 1e6, SLOT[0] / 1e6, SLOT[3] / 1e6);
        LOG.info("[sleep/opt] atlas double: {}", choice);
        Calibration.put("atlas2", on ? "on" : "off", choice);
    }

    /** the copies given up: every atlas's back copies freed (the atlas keeps drawing its current texture) */
    private static void dropAll() {
        for (AbstractTexture a : new java.util.ArrayList<>(STATES.keySet())) released(a);
    }

    private static final Map<AbstractTexture, State> STATES = new IdentityHashMap<>();
    private static State active;
    public static long ticks, rectCopies, writes, catchUpNs;
    private static int readFbo, drawFbo;

    /** level by level: the front's level on the read framebuffer, the back's on the draw one, a nearest blit per rect */
    private static void blitHistory(State s, int frontId, int[] skip, int nSkip) {
        int savedRead = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int savedDraw = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        if (scissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        if (readFbo == 0) {
            readFbo = org.lwjgl.opengl.GL30C.glGenFramebuffers();
            drawFbo = org.lwjgl.opengl.GL30C.glGenFramebuffers();
        }
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, readFbo);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
        for (int l = 0; l <= s.levels && (s.w >> l) > 0 && (s.h >> l) > 0; l++) {
            org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, frontId, l);
            org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, s.target, l);
            for (int[] r : s.history) {
                for (int i = 1; i < r[0]; i += 4) {
                    if (skip != null && rewritten(skip, nSkip, r, i)) continue;
                    int x = r[i] >> l, y = r[i + 1] >> l, rw = r[i + 2] >> l, rh = r[i + 3] >> l;
                    if (rw <= 0 || rh <= 0) continue;
                    org.lwjgl.opengl.GL30C.glBlitFramebuffer(x, y, x + rw, y + rh, x, y, x + rw, y + rh, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
                    if (l == 0) rectCopies++;
                }
            }
        }
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, savedRead);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, savedDraw);
        if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
    }

    /** the animation tick of this atlas begins: returns the texture its writes go to (the back one) */
    public static int begin(AbstractTexture atlas, int frontId) {
        State s = STATES.get(atlas);
        // no GL query on the per-tick path: on the Pi's Mesa a query of a texture the GPU has not finished writing (the
        // previous tick's copies) waited for it - the wait the copies were meant to remove (11-20); a rebuilt atlas
        // (TextureAtlas.upload, resource reload) drops the state through uploaded()
        if (s == null) {
            GlStateManager._bindTexture(frontId);
            int w = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            int h = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
            s = new State();
            s.w = w;
            s.h = h;
            s.levels = Math.min(GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL), 8);
            for (int c = 1; c < COPIES; c++) {
                int t = TextureUtil.generateTextureId();
                TextureUtil.prepareImage(t, s.levels, w, h);
                for (int l = 0; l <= s.levels && (w >> l) > 0 && (h >> l) > 0; l++)
                    GL43C.glCopyImageSubData(frontId, GL11C.GL_TEXTURE_2D, l, 0, 0, 0, t, GL11C.GL_TEXTURE_2D, l, 0, 0, 0, w >> l, h >> l, 1);
                copyParams(frontId, t);
                s.backs.addLast(t);
            }
            STATES.put(atlas, s);
            LOG.info("[sleep/opt] atlas double: {} copies of atlas {} ({}x{}, {} levels)", COPIES, frontId, w, h, s.levels);
        }
        // the oldest back copy, brought up to date: what the ticks since it was drawn changed (all in the front now)
        s.target = s.backs.pollFirst();
        long t0 = System.nanoTime();
        if (WHOLE) {
            // 11-20 Pi: glCopyImageSubData is a CPU copy on V3D (~100+ us a call, mostly per call): one per level
            for (int l = 0; l <= s.levels && (s.w >> l) > 0 && (s.h >> l) > 0; l++)
                GL43C.glCopyImageSubData(frontId, GL11C.GL_TEXTURE_2D, l, 0, 0, 0, s.target, GL11C.GL_TEXTURE_2D, l, 0, 0, 0, s.w >> l, s.h >> l, 1);
            rectCopies++;
        } else if (BLIT) {
            if (!BLIT_LATE) blitHistory(s, frontId, null, 0);
        } else if (replayNow = REPLAY && !GpuAnim.pbo()) {   // (the ring's unpack buffer binding: rects then)
            replay(s, frontId);
        } else {
            for (int[] r : s.history) {
                for (int i = 1; i < r[0]; i += 4) {
                    int x = r[i], y = r[i + 1], rw = r[i + 2], rh = r[i + 3];
                    for (int l = 0; l <= s.levels && (rw >> l) > 0 && (rh >> l) > 0; l++)
                        GL43C.glCopyImageSubData(frontId, GL11C.GL_TEXTURE_2D, l, x >> l, y >> l, 0, s.target, GL11C.GL_TEXTURE_2D, l, x >> l, y >> l, 0, rw >> l, rh >> l, 1);
                    rectCopies++;
                }
            }
        }
        catchUpNs += System.nanoTime() - t0;
        s.nCur = 1;
        if (replayNow) {
            Ops o = s.freeOps.pollFirst();
            if (o == null) o = new Ops();
            o.n = 0;
            o.pxN = 0;
            s.curOps = o;
        }
        active = s;
        return s.target;
    }

    public static long replayOps, replayCopies, replayUploads, replayRects;

    private static void replay(State s, int frontId) {
        boolean bound = false;
        for (Ops o : s.opsHistory) {
            for (int k = 0; k < o.n; k += 9) {
                int kind = o.op[k], x = o.op[k + 1], y = o.op[k + 2], w = o.op[k + 3], h = o.op[k + 4], levels = o.op[k + 5];
                replayOps++;
                if (kind == Ops.COPY) {
                    int sheet = o.op[k + 6], sx = o.op[k + 7], sy = o.op[k + 8];
                    for (int l = 0; l < levels; l++)
                        GL43C.glCopyImageSubData(sheet, GL11C.GL_TEXTURE_2D, l, sx >> l, sy >> l, 0, s.target, GL11C.GL_TEXTURE_2D, l, x >> l, y >> l, 0, w >> l, h >> l, 1);
                    replayCopies++;
                } else if (kind == Ops.UPLOAD) {
                    if (!bound) {   // vanilla's own upload sets these before each of its uploads
                        GlStateManager._bindTexture(s.target);
                        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
                        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
                        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
                        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
                        bound = true;
                    }
                    long at = org.lwjgl.system.MemoryUtil.memAddress(o.px, 0) + 4L * o.op[k + 6];
                    for (int l = 0; l < levels; l++) {
                        int lw = w >> l, lh = h >> l;
                        GL11C.nglTexSubImage2D(GL11C.GL_TEXTURE_2D, l, x >> l, y >> l, lw, lh, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, at);
                        at += 4L * lw * lh;
                    }
                    replayUploads++;
                } else {
                    for (int l = 0; l <= s.levels && (w >> l) > 0 && (h >> l) > 0; l++)
                        GL43C.glCopyImageSubData(frontId, GL11C.GL_TEXTURE_2D, l, x >> l, y >> l, 0, s.target, GL11C.GL_TEXTURE_2D, l, x >> l, y >> l, 0, w >> l, h >> l, 1);
                    replayRects++;
                }
            }
        }
    }

    /** GpuAnim's frame copy from a sprite sheet: levels 0..levels-1 from (sx, sy) to (x, y) */
    public static void recordCopy(int sheet, int sx, int sy, int x, int y, int w, int h, int levels) {
        State s = active;
        if (s == null) return;
        if (replayNow) s.curOps.add(Ops.COPY, x, y, w, h, levels, sheet, sx, sy);
        rect(s, x, y, w, h);
    }

    /** vanilla's upload of a sprite's frame (SpriteContents.upload: the interpolated ones in an animation tick) */
    public static void recordUpload(int x, int y, int w, int h, int frameX, int frameY, com.mojang.blaze3d.platform.NativeImage[] mips) {
        State s = active;
        if (s == null) return;
        if (replayNow) {
            Ops o = s.curOps;
            int levels = 0, off = o.pxN;
            for (int l = 0; l < mips.length && (w >> l) > 0 && (h >> l) > 0; l++) {   // the bound of the upload loop
                int lw = w >> l, lh = h >> l, fx = frameX >> l, fy = frameY >> l;
                o.ensurePx(lw * lh);
                com.mojang.blaze3d.platform.NativeImage m = mips[l];
                for (int py = 0; py < lh; py++)
                    for (int px = 0; px < lw; px++) o.px.put(o.pxN++, m.getPixelRGBA(fx + px, fy + py));
                levels++;
            }
            o.add(Ops.UPLOAD, x, y, w, h, levels, off, 0, 0);
        }
        rect(s, x, y, w, h);
    }

    /** a write of this tick at (x, y, w, h) - into the back texture */
    public static void record(int x, int y, int w, int h) {
        State s = active;
        if (s == null) return;
        if (replayNow) s.curOps.add(Ops.RECT, x, y, w, h, 0, 0, 0, 0);
        rect(s, x, y, w, h);
    }

    private static void rect(State s, int x, int y, int w, int h) {
        if (s.nCur + 4 > s.cur.length) s.cur = java.util.Arrays.copyOf(s.cur, s.cur.length * 2 + 1);
        s.cur[s.nCur++] = x;
        s.cur[s.nCur++] = y;
        s.cur[s.nCur++] = w;
        s.cur[s.nCur++] = h;
        writes++;
    }

    /** the same rect (a sprite's whole area) among this tick's writes */
    private static boolean rewritten(int[] cur, int n, int[] r, int i) {
        for (int k = 1; k + 3 < n; k += 4)
            if (cur[k] == r[i] && cur[k + 1] == r[i + 1] && cur[k + 2] == r[i + 2] && cur[k + 3] == r[i + 3]) return true;
        return false;
    }

    /** the tick's writes are done: back and front swap (the atlas draws the back one from now on) */
    public static int end(AbstractTexture atlas, int frontId) {
        State s = active;
        active = null;
        if (s == null) return frontId;
        if (BLIT && BLIT_LATE) {
            long t0 = System.nanoTime();
            blitHistory(s, frontId, s.cur, s.nCur);
            catchUpNs += System.nanoTime() - t0;
        }
        s.cur[0] = s.nCur;
        s.history.addLast(java.util.Arrays.copyOf(s.cur, s.nCur));
        while (s.history.size() > COPIES - 1) s.history.pollFirst();
        if (s.curOps != null) {
            s.opsHistory.addLast(s.curOps);
            s.curOps = null;
        }
        while (s.opsHistory.size() > COPIES - 1) s.freeOps.addLast(s.opsHistory.pollFirst());
        if (!replayNow) while (!s.opsHistory.isEmpty()) s.freeOps.addLast(s.opsHistory.pollFirst());   // (stale once the ring is on)
        s.nCur = 1;
        if (PARAMS_EVERY_TICK) copyParams(frontId, s.target);   // (a glGet each tick: waits on the Pi - off by default)
        int newFront = s.target;
        s.backs.addLast(frontId);
        if (++ticks % 2400 == 0 || ticks == 100)
            LOG.info("[sleep/opt] atlas double ({}): {} ticks, {} rect copies, {} writes, catch-up {} ms a tick", WHOLE ? "whole levels" : BLIT ? (BLIT_LATE ? "late blits" : "blits") : replayNow ? "replay" : "rects",
                    ticks, rectCopies, writes, String.format("%.3f", catchUpNs / 1e6 / ticks)
                    + (REPLAY ? String.format(" (replayed %d: %d sheet copies, %d uploads, %d front rects)", replayOps, replayCopies, replayUploads, replayRects) : ""));
        return newFront;
    }

    /** the atlas was (re)built (TextureAtlas.upload): the copies are stale - made again at the next tick */
    public static void uploaded(AbstractTexture atlas) {
        released(atlas);
    }

    /** the atlas texture is released: its back copy goes too */
    public static void released(AbstractTexture atlas) {
        State s = STATES.remove(atlas);
        if (s == null) return;
        for (int t : s.backs) TextureUtil.releaseTextureId(t);
        for (java.util.ArrayDeque<Ops> q : java.util.List.of(s.opsHistory, s.freeOps))
            for (Ops o : q) org.lwjgl.system.MemoryUtil.memFree(o.px);
        if (s.curOps != null) org.lwjgl.system.MemoryUtil.memFree(s.curOps.px);
        if (active == s) active = null;
    }

    private static void copyParams(int from, int to) {
        GlStateManager._bindTexture(from);
        int min = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER);
        int mag = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER);
        int ws = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S);
        int wt = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T);
        GlStateManager._bindTexture(to);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, min);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, mag);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, ws);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, wt);
    }
}
