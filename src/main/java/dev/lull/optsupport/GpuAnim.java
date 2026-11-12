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
        blit = fbo && !Boolean.getBoolean("lab.gpuAnim.noBlit") && (!copy || Boolean.getBoolean("lab.gpuAnim.forceBlit"));   // forceBlit: the blit path where copy_image exists too (to check it)
        dsa = (caps.OpenGL45 || caps.GL_ARB_direct_state_access && caps.GL_ARB_buffer_storage) && !Boolean.getBoolean("lab.gpuAnim.noDsa");   // noDsa: the bound-buffer ring (to check it)
        pboCapable = (copy || blit) && (dsa || (caps.OpenGL44 || caps.GL_ARB_buffer_storage) && caps.OpenGL30);
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
        if (INTERP && !interpBroken) tuneTick(ns, tickHadInterp);
        tickHadInterp = false;
        if (++ticks % 2400 == 0 || ticks == 200)   // (once early too: a short measured run sees it)
            LOG.info("[sleep/opt] gpu anim: {} atlas ticks ({} empty ones skipped), {} ms each; {} frame copies ({} per tick, {} us each, {} ms per tick), {} interpolated frames per tick (vanilla's path; their upload {} ms per tick, through the ring {} ms per tick, {} ring waits), {} sheets, {} blended on the GPU{}",
                    ticks, emptyAtlasTicks, String.format(java.util.Locale.ROOT, "%.3f", tickNs / 1e6 / ticks), copies, String.format(java.util.Locale.ROOT, "%.1f", (double) copies / ticks),
                    String.format(java.util.Locale.ROOT, "%.1f", copyNs / 1e3 / Math.max(1, copies)), String.format(java.util.Locale.ROOT, "%.3f", copyNs / 1e6 / ticks),
                    String.format(java.util.Locale.ROOT, "%.1f", (double) (SpriteMix.fast + SpriteMix.slow) / ticks), String.format(java.util.Locale.ROOT, "%.3f", interpUploadNs / 1e6 / ticks), String.format(java.util.Locale.ROOT, "%.3f", pboNs / 1e6 / ticks), ringWaits, sheetsMade, gpuBlends,
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
    static final boolean PBO_FLAG = ON && Boolean.getBoolean("lab.gpuAnim.pbo");

    /**
     * the ring for interpolated frames: -Dlab.gpuAnim.pbo only. On Android's LTW (bound buffers, the copy a blit) it cost
     * more than vanilla's uploads: 3.5 vs 2.7 ms an atlas tick - the translator copies an unpack from a buffer too.
     */
    static boolean pbo() {
        return pboCapable && PBO_FLAG;
    }

    static boolean dsa;
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
        interpEnd();
        if (pbo != 0 && segUsed > 0) {
            FENCES[seg] = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11C.glFlush();
        }
    }

    /** in place of SpriteContents.upload during an atlas's tick (only interpolated frames still upload); false: vanilla */
    public static boolean ringUpload(SpriteContents c, int x, int y, int frameX, int frameY, NativeImage[] data) {
        if (atlas == 0 || !pbo()) return false;
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
            int flags = org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44C.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44C.GL_MAP_COHERENT_BIT;
            if (dsa) {
                pbo = GL45C.glCreateBuffers();
                GL45C.glNamedBufferStorage(pbo, (long) SEG * NSEG, flags);
                pboPtr = MemoryUtil.memAddress(GL45C.glMapNamedBufferRange(pbo, 0, (long) SEG * NSEG, flags));
            } else {   // bind-based (no DSA: Android's LTW, buffer storage from EXT_buffer_storage)
                pbo = org.lwjgl.opengl.GL15C.glGenBuffers();
                org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, pbo);
                org.lwjgl.opengl.GL44C.glBufferStorage(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, (long) SEG * NSEG, flags);
                java.nio.ByteBuffer m = org.lwjgl.opengl.GL30C.glMapBufferRange(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0, (long) SEG * NSEG, flags);
                org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
                if (m == null) {   // the mapping refused: no ring from now on
                    org.lwjgl.opengl.GL15C.glDeleteBuffers(pbo);
                    pbo = 0;
                    pboCapable = false;
                    LOG.info("[sleep/opt] gpu anim: the upload ring could not be mapped here: interpolated frames the vanilla way");
                    return false;
                }
                pboPtr = MemoryUtil.memAddress(m);
            }
            segUsed = 0;
            LOG.info("[sleep/opt] gpu anim: interpolated frames through a mapped ring ({})", dsa ? "direct state access" : "bound buffers");
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
            if (dsa) {
                GL45C.glTextureSubImage2D(stage, k, 0, 0, lw, lh, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, off);
            } else {
                GlStateManager._bindTexture(stage);
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, k, 0, 0, lw, lh, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, off);
            }
            segUsed += lw * lh * 4;
        }
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        if (!dsa) GlStateManager._bindTexture(atlas);
        for (int k = 0; k < levels; k++) {   // into the atlas by a GPU copy, as the plain frames
            if (blit) {
                if (!blitCopy(stage, k, 0, 0, x >> k, y >> k, w >> k, h >> k)) break;
            } else {
                GL43C.glCopyImageSubData(stage, GL11C.GL_TEXTURE_2D, k, 0, 0, 0, atlas, GL11C.GL_TEXTURE_2D, k, x >> k, y >> k, 0, w >> k, h >> k, 1);
            }
        }
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

    // ------------------------------------------------------------------ interpolated frames blended on the GPU

    /**
     * -Dlab.gpuAnim.noInterp=true: interpolated frames blended on the CPU and uploaded (SpriteMix), as before. On: the
     * blend is drawn into the atlas from the sprite's sheet - per mip level a quad whose fragments read both frames'
     * texels and take red, green and blue from a 256 x 256 table of vanilla's (int) (d * a + (1 - d) * b) for this d
     * (computed on the CPU, the same doubles: the same ints), alpha from the first frame. No upload a tick: on Android's
     * LTW the uploads of interpolated frames were 2.7 ms an atlas tick (most vanilla blocks with "interpolate": stems,
     * prismarine, sculk, campfires, furnaces, command blocks - ticked whether seen or not).
     */
    private static final boolean INTERP = !Boolean.getBoolean("lab.gpuAnim.noInterp");
    private static int interpProgram = -1, interpVao, interpFbo;
    private static int uSheet, uTable, uSrc0, uSrc1, uDst;
    private static boolean interpBroken, interpChecked;
    private static final Map<Long, Integer> TABLES = new java.util.HashMap<>();
    private static final int SHEET_UNIT = 15, TABLE_UNIT = 14;   // (above vanilla's tracked units: its cache untouched)
    public static long gpuBlends;

    private static final String INTERP_VSH = "#version 330\nvoid main() {\n"
            + "    gl_Position = vec4(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID >> 1) * 2 - 1), 0.0, 1.0);\n}\n";
    private static final String INTERP_FSH = "#version 330\n"
            + "uniform sampler2D Sheet;\nuniform sampler2D Table;\nuniform ivec3 Src0;\nuniform ivec2 Src1;\nuniform ivec2 Dst;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "    ivec2 p = ivec2(gl_FragCoord.xy) - Dst;\n"
            + "    vec4 a = texelFetch(Sheet, Src0.xy + p, Src0.z);\n"
            + "    vec4 b = texelFetch(Sheet, Src1 + p, Src0.z);\n"
            + "    ivec4 ai = ivec4(a * 255.0 + 0.5), bi = ivec4(b * 255.0 + 0.5);\n"
            + "    fragColor = vec4(texelFetch(Table, ivec2(bi.r, ai.r), 0).r, texelFetch(Table, ivec2(bi.g, ai.g), 0).r,\n"
            + "                     texelFetch(Table, ivec2(bi.b, ai.b), 0).r, a.a);\n"
            + "}\n";

    private static int compile(int type, String src) {
        int s = org.lwjgl.opengl.GL20C.glCreateShader(type);
        org.lwjgl.opengl.GL20C.glShaderSource(s, src);
        org.lwjgl.opengl.GL20C.glCompileShader(s);
        if (org.lwjgl.opengl.GL20C.glGetShaderi(s, org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS) == 0) {
            LOG.info("[sleep/opt] gpu anim: the blend shader did not compile ({}): interpolated frames blended on the CPU",
                    org.lwjgl.opengl.GL20C.glGetShaderInfoLog(s).trim().replace('\n', ' '));
            org.lwjgl.opengl.GL20C.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static boolean interpReady() {
        if (interpProgram >= 0) return interpProgram > 0;
        interpProgram = 0;
        int vs = compile(org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER, INTERP_VSH), fs = vs == 0 ? 0 : compile(org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER, INTERP_FSH);
        if (fs == 0) return false;
        int p = org.lwjgl.opengl.GL20C.glCreateProgram();
        org.lwjgl.opengl.GL20C.glAttachShader(p, vs);
        org.lwjgl.opengl.GL20C.glAttachShader(p, fs);
        org.lwjgl.opengl.GL20C.glLinkProgram(p);
        org.lwjgl.opengl.GL20C.glDeleteShader(vs);
        org.lwjgl.opengl.GL20C.glDeleteShader(fs);
        if (org.lwjgl.opengl.GL20C.glGetProgrami(p, org.lwjgl.opengl.GL20C.GL_LINK_STATUS) == 0) {
            LOG.info("[sleep/opt] gpu anim: the blend program did not link: interpolated frames blended on the CPU");
            org.lwjgl.opengl.GL20C.glDeleteProgram(p);
            return false;
        }
        uSheet = org.lwjgl.opengl.GL20C.glGetUniformLocation(p, "Sheet");
        uTable = org.lwjgl.opengl.GL20C.glGetUniformLocation(p, "Table");
        uSrc0 = org.lwjgl.opengl.GL20C.glGetUniformLocation(p, "Src0");
        uSrc1 = org.lwjgl.opengl.GL20C.glGetUniformLocation(p, "Src1");
        uDst = org.lwjgl.opengl.GL20C.glGetUniformLocation(p, "Dst");
        interpVao = org.lwjgl.opengl.GL30C.glGenVertexArrays();
        interpFbo = org.lwjgl.opengl.GL30C.glGenFramebuffers();
        interpProgram = p;
        LOG.info("[sleep/opt] gpu anim: interpolated frames blended on the GPU (vanilla's rounding through a table)");
        return true;
    }

    /** vanilla's mix for this d, every (a, b): an R8 texture, row a, column b (kept: a sprite's d values repeat) */
    private static int table(double d) {
        long key = Double.doubleToRawLongBits(d);
        Integer t = TABLES.get(key);
        if (t != null) return t;
        if (TABLES.size() >= 256) {   // (far more than vanilla's sprites use: frame times are short)
            for (int id : TABLES.values()) TextureUtil.releaseTextureId(id);
            TABLES.clear();
        }
        ByteBuffer b = MemoryUtil.memAlloc(256 * 256);
        for (int a = 0; a < 256; a++)
            for (int c = 0; c < 256; c++) b.put(a << 8 | c, (byte) SpriteMix.mix(d, a, c));
        int id = TextureUtil.generateTextureId();
        int prevUnit = GL11C.glGetInteger(org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE);   // raw, put back raw (vanilla tracks 12 units)
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + TABLE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30C.GL_R8, 256, 256, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, b);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        org.lwjgl.opengl.GL13C.glActiveTexture(prevUnit);
        MemoryUtil.memFree(b);
        TABLES.put(key, id);
        return id;
    }

    /**
     * In place of the CPU blend and upload of an interpolated frame (SpriteInterpolateMixin): frame (fx0, fy0) and
     * (fx1, fy1) of the sprite's sheet, pixel positions at level 0, blended with d into the atlas at (x, y), every mip
     * level as upload would (NeoForge's bound). false: nothing drawn - the CPU way.
     */
    public static boolean interpolate(SpriteContents c, int x, int y, int fx0, int fy0, int fx1, int fy1, double d) {
        if (!INTERP || atlas == 0 || interpBroken || c.byMipLevel.length == 0 || c.byMipLevel[0].format() != NativeImage.Format.RGBA) return false;
        if (tunePhase == 0 || tunePhase == 1 || tunePhase == 5) return false;   // settling / measuring the CPU way, or decided for it
        if (!interpReady()) return false;
        long t0 = System.nanoTime();
        Integer sheet = SHEETS.get(c);
        if (sheet == null) {
            sheet = sheet(c.byMipLevel);
            SHEETS.put(c, sheet);
        }
        int table = table(d);
        interpBegin();   // (per blend, put back after it: vanilla's uploads and the blit copies between blends must find their own state)
        if (!selfTested) {
            selfTested = true;
            if (!selfTest(c, sheet, table, fx0, fy0, fx1, fy1, d)) {
                interpBroken = true;
                interpEnd();
                return false;
            }
        }
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + SHEET_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sheet);
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + TABLE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, table);
        org.lwjgl.opengl.GL13C.glActiveTexture(savedUnit);   // (vanilla's uploads in this tick bind through the active unit)
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, interpFbo);   // (a blit meanwhile binds its own)
        boolean ok = true;
        int w = c.width(), h = c.height();
        for (int k = 0; k < c.byMipLevel.length; k++) {
            int lw = w >> k, lh = h >> k;
            if (lw <= 0 || lh <= 0) break;   // NeoForge's bound, as in upload
            org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, atlas, k);
            if (!interpChecked) {
                int st = org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER);
                if (st != org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    LOG.info("[sleep/opt] gpu anim: the atlas cannot be drawn into here (status {}): interpolated frames blended on the CPU", st);
                    interpBroken = true;
                    ok = false;
                    break;
                }
            }
            GL11C.glViewport(x >> k, y >> k, lw, lh);
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);   // (the rectangle twice: a translator that bends the viewport cannot spill)
            GL11C.glScissor(x >> k, y >> k, lw, lh);
            org.lwjgl.opengl.GL20C.glUniform3i(uSrc0, fx0 >> k, fy0 >> k, k);
            org.lwjgl.opengl.GL20C.glUniform2i(uSrc1, fx1 >> k, fy1 >> k);
            org.lwjgl.opengl.GL20C.glUniform2i(uDst, x >> k, y >> k);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLE_STRIP, 0, 4);
        }
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        if (ok && !interpChecked) {
            interpChecked = true;
            int err = GL11C.glGetError();
            if (err != GL11C.GL_NO_ERROR) {
                LOG.info("[sleep/opt] gpu anim: the first GPU blend failed (GL error {}): interpolated frames blended on the CPU", err);
                interpBroken = true;
                ok = false;
            }
        }
        if (!ok) {
            interpEnd();
            return false;
        }
        interpEnd();
        gpuBlends++;
        long ns = System.nanoTime() - t0;
        interpUploadNs += ns;
        return true;
    }

    // which way is faster here: measured (-Dlab.gpuAnim.interp=gpu / cpu: fixed)
    private static final String INTERP_WAY = System.getProperty("lab.gpuAnim.interp", "tune");
    /**
     * 0 settling on the CPU, 1 CPU window, 2 GPU warm-up (sheets, tables, the self-test), 3 GPU window, 4 decided: GPU,
     * 5 decided: CPU
     */
    private static int tunePhase = "cpu".equals(INTERP_WAY) ? 5 : "gpu".equals(INTERP_WAY) ? 4 : 0;
    private static final int WARM = 40, WINDOW = 200, SETTLE = 20, EARLY = 30;
    private static long tuneNs, tuneTicks;
    private static double cpuPer;
    public static String interpChoice = "not decided";

    /**
     * The choice by whole atlas ticks (tickDone: the blends, the copies, the uploads and their waits together), not by
     * the blend call's own time: 11-12 on the N100 (Intel) the blends measured 653 against 791 us a frame and the GPU
     * was kept, but its atlas ticks were 3.0 ms against the CPU way's 1.7 - the uploads after GPU draws into the atlas
     * waited for them. The CPU way first (vanilla's cost), then the GPU's, stopped after 30 ticks when already 1.5x the
     * CPU's (an Intel atlas tick took ~10 ms with the GPU blends: a whole window was ~10 s of heavier ticks after joining
     * a world). The phone (LTW): ~2.0 against ~6.4 ms. The GPU is kept for a 10 % win; ticks without interpolated
     * frames are not counted.
     */
    private static final Samples TUNE = new Samples(WINDOW);
    private static boolean calibChecked;

    private static void tuneTick(long ns, boolean hadInterp) {
        if (tunePhase >= 4 || !hadInterp) return;
        if (!calibChecked) {   // measured on this machine before (Calibration): no measuring
            calibChecked = true;
            String c = Calibration.get("interp");
            if ("gpu".equals(c) || "cpu".equals(c)) {
                tunePhase = "gpu".equals(c) ? 4 : 5;
                interpChoice = c.toUpperCase(java.util.Locale.ROOT) + " (calibrated earlier: " + Calibration.get("interp.why") + ")";
                LOG.info("[sleep/opt] gpu anim: interpolated frames blended on the {}", interpChoice);
                return;
            }
        }
        if (net.minecraft.client.Minecraft.getInstance().screen != null) return;   // (a screen open: not counted)
        switch (tunePhase) {
            case 0 -> {
                if (++tuneTicks >= SETTLE) {
                    tunePhase = 1;
                    tuneTicks = 0;
                    TUNE.clear();
                }
            }
            case 1 -> {
                TUNE.add(ns);
                if (++tuneTicks >= WINDOW) {
                    cpuPer = TUNE.trimmed();
                    tunePhase = 2;
                    tuneTicks = tuneNs = 0;
                    TUNE.clear();
                }
            }
            case 2 -> {
                if (++tuneTicks >= WARM) {
                    tunePhase = 3;
                    tuneTicks = tuneNs = 0;
                    TUNE.clear();
                }
            }
            default -> {   // 3
                tuneNs += ns;
                TUNE.add(ns);
                ++tuneTicks;
                boolean early = tuneTicks >= EARLY && (double) tuneNs / tuneTicks > cpuPer * 1.5;
                if (tuneTicks >= WINDOW || early) {
                    double gpuPer = early ? (double) tuneNs / tuneTicks : TUNE.trimmed();
                    boolean gpu = !early && gpuPer < cpuPer * 0.9;   // (a tie: the CPU, vanilla's way)
                    tunePhase = gpu ? 4 : 5;
                    interpChoice = String.format(java.util.Locale.ROOT, "%s (an atlas tick %.3f ms with the GPU blends%s, %.3f with the CPU's)", gpu ? "GPU" : "CPU",
                            gpuPer / 1e6, early ? " after " + tuneTicks + " ticks" : "", cpuPer / 1e6);
                    LOG.info("[sleep/opt] gpu anim: interpolated frames blended on the {}", interpChoice);
                    Calibration.put("interp", gpu ? "gpu" : "cpu", interpChoice);
                }
            }
        }
    }

    /** Calibration.reset (the settings' Recalibrate): measured again, from the next atlas tick */
    static void recalibrate() {
        if (!"tune".equals(INTERP_WAY)) return;
        tunePhase = 0;
        tuneTicks = tuneNs = 0;
        calibChecked = true;   // (the file was just emptied)
        TUNE.clear();
        interpChoice = "not decided";
    }

    /** interpolated frames went through interpolate this atlas tick (the GPU's or the CPU's) */
    private static boolean tickHadInterp;

    /** SpriteInterpolateMixin: an interpolated frame was blended (either way) in this atlas tick */
    public static void interpSeen() {
        tickHadInterp = true;
    }

    public static final boolean VERIFY_BLEND = VERIFY;

    private static boolean selfTested;

    /**
     * Before the first blend touches the atlas: the same draw (level 0) into a scratch texture three times the sprite's
     * size, filled with a marker, the blend aimed at its middle; read back whole (glReadPixels: GLES has it) and
     * compared - inside against the CPU blend (SpriteMix.mix), outside the marker kept. 11-12: on the phone (LTW) the
     * first version wiped the whole block atlas (the quad covered the attachment, not the sprite) - this finds such a
     * driver before the atlas is drawn into, and logs where the draw landed. The program and state are the blend's
     * (interpBegin ran).
     */
    private static boolean selfTest(SpriteContents c, int sheet, int table, int fx0, int fy0, int fx1, int fy1, double d) {
        int w = c.width(), h = c.height(), tw = w * 3, th = h * 3;
        NativeImage src = c.byMipLevel[0];
        int scratch = TextureUtil.generateTextureId();
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + SHEET_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scratch);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, tw, th, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sheet);
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + TABLE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, table);
        org.lwjgl.opengl.GL13C.glActiveTexture(savedUnit);
        int prevRead = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, interpFbo);
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, scratch, 0);
        int status = org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER);
        String verdict;
        boolean ok = false;
        if (status != org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE) {
            verdict = "scratch framebuffer incomplete (" + status + ")";
        } else {
            final int MARK = 0x7F3F1F0F;   // (bytes 0F 1F 3F 7F: R G B A)
            GL11C.glViewport(0, 0, tw, th);
            GL11C.glClearColor((MARK & 255) / 255f, (MARK >> 8 & 255) / 255f, (MARK >> 16 & 255) / 255f, (MARK >>> 24) / 255f);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            GL11C.glClearColor(0f, 0f, 0f, 0f);
            GL11C.glViewport(w, h, w, h);
            int[] vp = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL11C.glScissor(w, h, w, h);
            org.lwjgl.opengl.GL20C.glUniform3i(uSrc0, fx0, fy0, 0);
            org.lwjgl.opengl.GL20C.glUniform2i(uSrc1, fx1, fy1);
            org.lwjgl.opengl.GL20C.glUniform2i(uDst, w, h);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLE_STRIP, 0, 4);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            ByteBuffer px = MemoryUtil.memAlloc(tw * th * 4);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
            GL11C.glReadPixels(0, 0, tw, th, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, px);
            int inBad = 0, outChanged = 0, minX = tw, minY = th, maxX = -1, maxY = -1, firstBad = 0, firstWant = 0;
            for (int yy = 0; yy < th; yy++)
                for (int xx = 0; xx < tw; xx++) {
                    int got = px.getInt((yy * tw + xx) * 4);
                    boolean inside = xx >= w && xx < 2 * w && yy >= h && yy < 2 * h;
                    if (got != MARK) {
                        minX = Math.min(minX, xx); minY = Math.min(minY, yy); maxX = Math.max(maxX, xx); maxY = Math.max(maxY, yy);
                    }
                    if (inside) {
                        int px0 = xx - w, py0 = yy - h;
                        int a = src.getPixelRGBA(fx0 + px0, fy0 + py0), b = src.getPixelRGBA(fx1 + px0, fy1 + py0);
                        int want = a & 0xFF000000 | SpriteMix.mix(d, a >> 16 & 255, b >> 16 & 255) << 16 | SpriteMix.mix(d, a >> 8 & 255, b >> 8 & 255) << 8 | SpriteMix.mix(d, a & 255, b & 255);
                        if (got != want && inBad++ == 0) { firstBad = got; firstWant = want; }
                    } else if (got != MARK) outChanged++;
                }
            MemoryUtil.memFree(px);
            ok = inBad == 0 && outChanged == 0;
            verdict = String.format(java.util.Locale.ROOT, "%s: sprite %dx%d, viewport asked (%d,%d %dx%d) reads back (%d,%d %dx%d), written box %s, inside wrong %d of %d%s, outside changed %d",
                    ok ? "passed" : "FAILED", w, h, w, h, w, h, vp[0], vp[1], vp[2], vp[3],
                    maxX < 0 ? "none" : "(" + minX + "," + minY + ")-(" + maxX + "," + maxY + ")", inBad, w * h,
                    inBad > 0 ? String.format(" (first %08x, want %08x)", firstBad, firstWant) : "", outChanged);
        }
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, prevRead);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, interpFbo);
        TextureUtil.releaseTextureId(scratch);
        LOG.info("[sleep/opt] gpu anim: blend self-test {}{}", verdict, ok ? "" : " - the atlas is not drawn into (interpolated frames on the CPU)");
        return ok;
    }

    private static boolean interpBound, savedBlend, savedDepth, savedCull, savedScissorI;
    private static int savedProgram, savedVao, savedFboI, savedUnit;
    private static final int[] SAVED_VP = new int[4];
    private static final ByteBuffer SAVED_MASK = MemoryUtil.memAlloc(4);

    /**
     * A blend's state: the current one saved raw and the blend's set; put back raw right after the blend (interpEnd).
     * Not once a tick: 11-12 on the phone (LTW) a CPU-blended frame uploaded between two GPU blends, with the blend's
     * framebuffer and viewport still bound, wiped the block atlas black (the translator draws uploads itself). The
     * queries cost a desktop driver (atlas tick 0.23 -> 4.9 ms) - there the measured choice keeps the CPU way.
     */
    private static void interpBegin() {
        savedProgram = GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
        savedVao = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING);
        savedFboI = GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        savedUnit = GL11C.glGetInteger(org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, SAVED_VP);
        savedBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        savedDepth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        savedCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        savedScissorI = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, SAVED_MASK);
        if (savedBlend) GL11C.glDisable(GL11C.GL_BLEND);
        if (savedDepth) GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        if (savedCull) GL11C.glDisable(GL11C.GL_CULL_FACE);
        if (savedScissorI) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glColorMask(true, true, true, true);
        org.lwjgl.opengl.GL20C.glUseProgram(interpProgram);
        org.lwjgl.opengl.GL30C.glBindVertexArray(interpVao);
        org.lwjgl.opengl.GL20C.glUniform1i(uSheet, SHEET_UNIT);
        org.lwjgl.opengl.GL20C.glUniform1i(uTable, TABLE_UNIT);
        interpBound = true;
    }

    /** the atlas tick's end (tickEnd): the state as it was */
    static void interpEnd() {
        if (!interpBound) return;
        interpBound = false;
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, interpFbo);
        org.lwjgl.opengl.GL30C.glFramebufferTexture2D(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + SHEET_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + TABLE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        org.lwjgl.opengl.GL13C.glActiveTexture(savedUnit);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, savedFboI);
        org.lwjgl.opengl.GL30C.glBindVertexArray(savedVao);
        org.lwjgl.opengl.GL20C.glUseProgram(savedProgram);
        GL11C.glViewport(SAVED_VP[0], SAVED_VP[1], SAVED_VP[2], SAVED_VP[3]);
        GL11C.glColorMask(SAVED_MASK.get(0) != 0, SAVED_MASK.get(1) != 0, SAVED_MASK.get(2) != 0, SAVED_MASK.get(3) != 0);
        if (savedBlend) GL11C.glEnable(GL11C.GL_BLEND);
        if (savedDepth) GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        if (savedCull) GL11C.glEnable(GL11C.GL_CULL_FACE);
        if (savedScissorI) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();   // (another vertex array was bound meanwhile)
    }

    /** -Dlab.gpuAnim.verify: the atlas region (level 0) against the CPU blend (SpriteMix into a scratch frame) */
    public static void verifyBlend(NativeImage expected, int x, int y, int w, int h) {
        if (!VERIFY || !readBack) return;
        ByteBuffer b = MemoryUtil.memAlloc(w * h * 4);
        GL45C.glGetTextureSubImage(atlas, 0, x, y, 0, w, h, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, b);
        int diff = 0;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++)
                if (b.getInt((j * w + i) * 4) != expected.getPixelRGBA(i, j)) diff++;
        MemoryUtil.memFree(b);
        verified++;
        if (diff > 0 && mismatches++ < 5) LOG.warn("[sleep/opt] gpu anim: a GPU-blended frame differs from the CPU blend at {},{} ({}x{}): {} pixels", x, y, w, h, diff);
    }

    /** the sprite closed (a resource reload): its sheet goes */
    public static void release(SpriteContents c) {
        Integer sheet = SHEETS.remove(c);
        if (sheet != null) TextureUtil.releaseTextureId(sheet);
        Integer stage = STAGING.remove(c);
        if (stage != null) TextureUtil.releaseTextureId(stage);
    }
}
