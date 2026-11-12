package dev.lull.optsupport;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.opt.GpuAnim (opt mixins GpuAnim*): an animated sprite's frame change copied on the GPU. Vanilla uploads the new frame from the CPU
 * every time (SpriteContents.upload: one glTexSubImage2D per mip level, for every animated sprite whose frame changes);
 * on the N100 the texture tick was 3.8 of the client tick's 6 ms in a flight. Here each animated sprite's whole sheet
 * (every frame, every mip level) goes up once into its own texture through vanilla's own uploader, and a frame change is
 * glCopyImageSubData from the sheet into the atlas, level by level with vanilla's (NeoForge's) mip bound: the same
 * pixels. Interpolated sprites keep vanilla's path (their blend is rounded in double on the CPU).
 * -Dlab.gpuAnim.verify: every copy read back (level 0) and compared with the sheet's pixels.
 */
public final class GpuAnim {
    private GpuAnim() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final boolean ON = Boolean.getBoolean("lab.opt.GpuAnim") || Boolean.getBoolean("lab.opt.all");
    private static Boolean capable;

    /**
     * The switch the mixins ask: on, and the driver has what the copies call - GL 4.5, or copy_image, direct state
     * access, buffer storage and texture sub-image reads on GL 3.2 (fences). Asked on the render thread (its
     * capabilities); elsewhere false without deciding. 1.2.0 asked nothing and Android launchers' GL4ES (GL 2.1)
     * aborted the game at the first animated frame (glCopyImageSubData: no such function).
     */
    public static boolean on() {
        if (!ON) return false;
        Boolean c = capable;
        if (c != null) return c;
        org.lwjgl.opengl.GLCapabilities caps;
        try {
            caps = org.lwjgl.opengl.GL.getCapabilities();
        } catch (Throwable t) {
            return false;   // not a GL thread: undecided
        }
        boolean copy = caps.OpenGL43 || caps.GL_ARB_copy_image;
        boolean fbo = caps.OpenGL30 || caps.GL_ARB_framebuffer_object;
        blit = !copy && fbo && !Boolean.getBoolean("lab.gpuAnim.noBlit");
        pboCapable = copy && (caps.OpenGL45 || caps.GL_ARB_direct_state_access && caps.GL_ARB_buffer_storage);
        readBack = caps.OpenGL45 || caps.GL_ARB_get_texture_sub_image;
        capable = c = copy || blit;
        LOG.info(c ? "[sleep/opt] gpu anim: frame changes copied on the GPU by " + (blit ? "framebuffer blit (no copy_image here)" : "glCopyImageSubData")
                : "[sleep/opt] gpu anim: not available (needs GL 4.3 / copy_image, or framebuffer objects): animated textures the vanilla way");
        return c;
    }
    private static final boolean VERIFY = Boolean.getBoolean("lab.gpuAnim.verify");
    /** the atlas cycleAnimationFrames is ticking (0 outside it: the first frames at stitching go vanilla's way) */
    public static int atlas;
    private static final Map<SpriteContents, Integer> SHEETS = new IdentityHashMap<>();
    /** an interpolated sprite's staging texture (its size, every mip level): the ring uploads there, a GPU copy takes it on
     *  into the atlas - an upload from a buffer straight into the atlas made the N100's (Intel) drawing from it slower (1.20.1: 104 -> 40 fps) */
    private static final Map<SpriteContents, Integer> STAGING = new IdentityHashMap<>();
    public static long copies, sheetsMade, verified, mismatches, copyNs, ticks, tickNs, interpolated, emptyAtlasTicks;
    public static long uploadT0, interpUploadNs, interpUploads;

    /** in place of SpriteContents.upload(x, y, frameX, frameY, byMipLevel); false: vanilla uploads */
    public static boolean copyFrame(SpriteContents c, NativeImage[] mips, int x, int y, int frameX, int frameY) {
        if (atlas == 0 || mips.length == 0 || mips[0].format() != NativeImage.Format.RGBA || blitBroken) return false;
        long t0 = System.nanoTime();
        Integer sheet = SHEETS.get(c);
        if (sheet == null) {
            sheet = sheet(mips);
            SHEETS.put(c, sheet);
            if (sheetsMade++ == 0) LOG.info("[sleep/opt] gpu anim: active - the first sprite sheet on the GPU, frame changes copied there");
        }
        int w = c.width(), h = c.height();
        for (int i = 0; i < mips.length; i++) {
            if ((w >> i) <= 0 || (h >> i) <= 0) break;   // NeoForge's bound, as in upload
            if (blit) {
                if (!blitCopy(sheet, i, frameX >> i, frameY >> i, x >> i, y >> i, w >> i, h >> i)) return i == 0 ? false : true;
            } else {
                GL43C.glCopyImageSubData(sheet, GL11C.GL_TEXTURE_2D, i, frameX >> i, frameY >> i, 0, atlas, GL11C.GL_TEXTURE_2D, i, x >> i, y >> i, 0, w >> i, h >> i, 1);
            }
        }
        copyNs += System.nanoTime() - t0;
        if (VERIFY && readBack) verify(mips[0], x, y, frameX, frameY, w, h);
        copies++;
        return true;
    }

    /** the whole sheet, every mip level, through vanilla's uploader into a texture of its own; the atlas bound again */
    private static int sheet(NativeImage[] mips) {
        int tex = TextureUtil.generateTextureId();
        TextureUtil.prepareImage(tex, mips.length - 1, mips[0].getWidth(), mips[0].getHeight());
        for (int i = 0; i < mips.length; i++)
            mips[i].upload(i, 0, 0, 0, 0, mips[i].getWidth(), mips[i].getHeight(), mips.length > 1, false);
        GlStateManager._bindTexture(atlas);
        return tex;
    }

    private static void verify(NativeImage src, int x, int y, int frameX, int frameY, int w, int h) {
        ByteBuffer b = MemoryUtil.memAlloc(w * h * 4);
        GL45C.glGetTextureSubImage(atlas, 0, x, y, 0, w, h, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, b);
        boolean same = true;
        for (int j = 0; j < h && same; j++)
            for (int i = 0; i < w; i++)
                if (b.getInt((j * w + i) * 4) != src.getPixelRGBA(frameX + i, frameY + j)) {
                    same = false;
                    break;
                }
        MemoryUtil.memFree(b);
        verified++;
        if (!same && mismatches++ < 5) LOG.warn("[sleep/opt] gpu anim: a copied frame differs from its sheet at {},{} ({}x{})", x, y, w, h);
    }

    /** around TextureAtlas.cycleAnimationFrames: the whole animation tick of an atlas, the copies part of it */
    public static void tickDone(long ns) {
        tickNs += ns;
        if (++ticks % 2400 == 0)
            LOG.info("[sleep/opt] gpu anim: {} atlas ticks ({} empty ones skipped), {} ms each; {} frame copies ({} per tick, {} us each, {} ms per tick), {} interpolated frames per tick (vanilla's path; their upload {} ms per tick, through the ring {} ms per tick, {} ring waits), {} sheets{}",
                    ticks, emptyAtlasTicks, String.format(java.util.Locale.ROOT, "%.3f", tickNs / 1e6 / ticks), copies, String.format(java.util.Locale.ROOT, "%.1f", (double) copies / ticks),
                    String.format(java.util.Locale.ROOT, "%.1f", copyNs / 1e3 / Math.max(1, copies)), String.format(java.util.Locale.ROOT, "%.3f", copyNs / 1e6 / ticks),
                    String.format(java.util.Locale.ROOT, "%.1f", (double) (SpriteMix.fast + SpriteMix.slow) / ticks), String.format(java.util.Locale.ROOT, "%.3f", interpUploadNs / 1e6 / ticks), String.format(java.util.Locale.ROOT, "%.3f", pboNs / 1e6 / ticks), ringWaits, sheetsMade,
                    VERIFY ? ", verified " + verified + ", mismatches " + mismatches : "");
    }

    /**
     * -Dlab.gpuAnim.pbo (off by default): an interpolated frame (blended on the CPU as vanilla does)
     * goes up through a persistently mapped ring in memory both sides see: its rows copied in, glTextureSubImage2D from
     * the ring's offset - the driver neither copies client memory nor waits. On the N100 the vanilla uploads were 0.7-0.8
     * of an animated atlas's ~1 ms tick (~20 us per glTexSubImage2D from client memory, ~4 us for a GPU copy).
     * Mixed on the N100: the 1.21.1 voxel flight's tick 4.1-4.5 -> 3.45 ms, but 1.20.1's entities scene (GPU-bound) 2.48 (copies
     * only) -> 3.15 ms - so opt-in; the copies alone (the default with the feature) gain or tie everywhere measured.
     */
    static final boolean PBO = ON && Boolean.getBoolean("lab.gpuAnim.pbo");
    // 8 segments of 256 KiB (a tick's interpolated frames are ~15 KiB): a segment comes back ~4 client ticks later, long
    // read by then - with 3 the fence wait blocked the render thread in a GPU-bound scene (1.20.1 N100: tick 2.5 -> 3.6 ms)
    private static final int SEG = 1 << 18, NSEG = 8;
    private static int pbo, seg, segUsed;
    private static long pboPtr;
    private static final long[] FENCES = new long[NSEG];
    public static long pboUploads, pboNs, ringWaits;

    /** an atlas's animation tick begins: the next ring segment, once the GPU has read it */
    public static void tickBegin() {
        if (pbo == 0) return;
        seg = (seg + 1) % NSEG;
        segUsed = 0;
        if (FENCES[seg] != 0) {
            if (org.lwjgl.opengl.GL32C.glClientWaitSync(FENCES[seg], 0, 0) == org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED) ringWaits++;   // not read yet: counted
            org.lwjgl.opengl.GL32C.glClientWaitSync(FENCES[seg], org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);   // the fence sent to the GPU, else the wait runs to its timeout
            org.lwjgl.opengl.GL32C.glDeleteSync(FENCES[seg]);
            FENCES[seg] = 0;
        }
    }

    /** an atlas's animation tick ends: a fence after the segment's uploads */
    public static void tickEnd() {
        blitEnd();
        if (pbo != 0 && segUsed > 0) {
            FENCES[seg] = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11C.glFlush();
        }
    }

    /** in place of SpriteContents.upload during an atlas's tick (only interpolated frames still upload); false: vanilla */
    public static boolean ringUpload(SpriteContents c, int x, int y, int frameX, int frameY, NativeImage[] data) {
        if (!PBO || atlas == 0 || !pboCapable) return false;
        int w = c.width(), h = c.height();
        long need = 0;
        int levels = 0;
        for (int k = 0; k < data.length; k++) {
            if ((w >> k) <= 0 || (h >> k) <= 0) break;   // NeoForge's bound, as in upload
            if (data[k].format() != NativeImage.Format.RGBA) return false;
            need += (long) (w >> k) * (h >> k) * 4;
            levels++;
        }
        if (need > SEG) return false;
        if (pbo == 0) {
            pbo = GL45C.glCreateBuffers();
            int flags = org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44C.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44C.GL_MAP_COHERENT_BIT;
            GL45C.glNamedBufferStorage(pbo, (long) SEG * NSEG, flags);
            pboPtr = MemoryUtil.memAddress(GL45C.glMapNamedBufferRange(pbo, 0, (long) SEG * NSEG, flags));
            segUsed = 0;
        }
        if (segUsed + need > SEG) return false;
        long t0 = System.nanoTime();
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, pbo);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        Integer stage = STAGING.get(c);
        if (stage == null) {
            stage = TextureUtil.generateTextureId();
            TextureUtil.prepareImage(stage, levels - 1, w, h);
            GlStateManager._bindTexture(atlas);
            STAGING.put(c, stage);
        }
        for (int k = 0; k < levels; k++) {
            NativeImage img = data[k];
            long src = img.pixels;
            int iw = img.getWidth(), lw = w >> k, lh = h >> k, fx = frameX >> k, fy = frameY >> k;
            long off = (long) seg * SEG + segUsed;
            for (int r = 0; r < lh; r++) MemoryUtil.memCopy(src + ((long) (fy + r) * iw + fx) * 4, pboPtr + off + (long) r * lw * 4, (long) lw * 4);
            GL45C.glTextureSubImage2D(stage, k, 0, 0, lw, lh, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, off);
            segUsed += lw * lh * 4;
        }
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int k = 0; k < levels; k++)   // into the atlas by a GPU copy, as the plain frames
            GL43C.glCopyImageSubData(stage, GL11C.GL_TEXTURE_2D, k, 0, 0, 0, atlas, GL11C.GL_TEXTURE_2D, k, x >> k, y >> k, 0, w >> k, h >> k, 1);
        pboNs += System.nanoTime() - t0;
        pboUploads++;
        if (VERIFY && readBack) verify(data[0], x, y, frameX, frameY, w, h);
        return true;
    }

    // ------------------------------------------------------------------ the blit path (no copy_image)

    /** frame copies by glBlitFramebuffer (GL 3.0 / GLES 3.0), the drivers without copy_image (Android's LTW: GL 3.3) */
    static boolean blit, pboCapable, readBack;
    private static boolean blitBroken, blitChecked, blitBound;
    private static int readFbo, drawFbo, savedRead, savedDraw;
    private static boolean savedScissor;
    public static long blits;

    /**
     * One mip level of a frame: the sheet's level on the read framebuffer, the atlas's on the draw one, a nearest blit of
     * the same size - texel for texel. The bindings are saved once an atlas tick (the first copy) and put back at its
     * end (blitEnd). false: the first blit failed its checks - the feature stops, vanilla uploads from now on.
     */
    private static boolean blitCopy(int sheet, int level, int sx, int sy, int dx, int dy, int w, int h) {
        if (!blitBound) {
            savedRead = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING);
            savedDraw = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            savedScissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            if (savedScissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);   // a blit is clipped by it
            if (readFbo == 0) {
                readFbo = org.lwjgl.opengl.GL30C.glGenFramebuffers();
                drawFbo = org.lwjgl.opengl.GL30C.glGenFramebuffers();
            }
            org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, readFbo);
            org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
            blitBound = true;
        }
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, sheet, level);
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, atlas, level);
        if (!blitChecked) {
            int r = org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER);
            int d = org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER);
            if (r != org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE || d != org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE) {
                blitBroken = true;
                LOG.info("[sleep/opt] gpu anim: framebuffer blit not usable here (status {} / {}): animated textures the vanilla way", r, d);
                blitEnd();
                return false;
            }
        }
        org.lwjgl.opengl.GL30C.glBlitFramebuffer(sx, sy, sx + w, sy + h, dx, dy, dx + w, dy + h, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        if (!blitChecked) {
            blitChecked = true;
            int err = GL11C.glGetError();
            if (err != GL11C.GL_NO_ERROR) {
                blitBroken = true;
                LOG.info("[sleep/opt] gpu anim: the first framebuffer blit failed (GL error {}): animated textures the vanilla way", err);
                blitEnd();
                return false;
            }
        }
        blits++;
        return true;
    }

    /** an atlas tick's end (and a failed check): the framebuffers and the scissor as they were */
    static void blitEnd() {
        if (!blitBound) return;
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, savedRead);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, savedDraw);
        if (savedScissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        blitBound = false;
    }

    /** the sprite closed (a resource reload): its sheet goes */
    public static void release(SpriteContents c) {
        Integer sheet = SHEETS.remove(c);
        if (sheet != null) TextureUtil.releaseTextureId(sheet);
        Integer stage = STAGING.remove(c);
        if (stage != null) TextureUtil.releaseTextureId(stage);
    }
}
