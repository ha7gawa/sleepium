package dev.lull.optsupport;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.voxel.lod=true (client, measurement prototype, notes/61): is a ray march through the voxel grid cheaper than the
 * raster it would replace for the far part of the view? Over ClientAuto's measured window it cycles three modes,
 * -Dlab.voxel.lod.modeSeconds (default 5) each: FULL (the raster as it is), NEAR (sections farther than
 * -Dlab.voxel.lod.near chunks, default 6, not drawn by ChunkMultiDraw; translucent sections are left alone) and RAY
 * (NEAR plus a full-screen pass that marches the loaded world, from where the ray leaves the near box, on the pixels
 * the raster left as sky). Per mode: frames, CPU frame time, GPU frame time (timestamp queries read a few frames late,
 * no stall) and the ray pass's own GPU time (the depth copy included); a screenshot of each mode's first round.
 *
 * <p>The grid is built once at the window's start from the client's loaded chunks within the render distance: a byte
 * per block (its map colour id; full opaque cubes, leaves and water count, plants and other shapes do not) in an R8UI
 * 3D texture, plus a 4x4x4 occupancy level for skipping empty space. Flat map colours, face shading and the terrain
 * fog: a picture to check that the rays hit the same terrain, not a renderer.
 */
public final class VoxelLod {
    public static final boolean ON = Boolean.getBoolean("lab.voxel.lod");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final int NEAR = Integer.getInteger("lab.voxel.lod.near", 6);
    private static final double MODE_S = Double.parseDouble(System.getProperty("lab.voxel.lod.modeSeconds", "5"));
    private static final String[] MODES = {"full", "near", "ray"};

    /** NEAR / RAY: ChunkMultiDraw skips sections outside the near box */
    public static boolean cutting;
    /** lab.voxel.compute: every terrain section cut (the GPU-meshed faces drawn instead) */
    public static boolean cutAll;
    private static int camCx, camCz;
    private static int mode = -1, lastMode = -1;
    private static long windowStart, modeStart;
    private static boolean active;
    private static final boolean[] SHOT = new boolean[3];

    // per mode: frames, cpu ns, gpu frames, gpu ns, ray frames, ray ns
    private static final long[][] ACC = new long[3][6];
    private static long lastFrameStart;
    private static int lastFrameMode = -1;

    private static final int RING = 8;
    private static final int[] QB = new int[RING], QE = new int[RING], QRB = new int[RING], QRE = new int[RING];
    private static final int[] QMODE = new int[RING];
    private static final boolean[] QRAY = new boolean[RING], QUSED = new boolean[RING];
    private static int slot;

    private VoxelLod() {}

    // ---- the window and the modes ----

    public static void start() {
        for (long[] a : ACC) java.util.Arrays.fill(a, 0);
        java.util.Arrays.fill(SHOT, false);
        java.util.Arrays.fill(QUSED, false);
        buildGrid();
        windowStart = System.nanoTime();
        mode = lastMode = -1;
        lastFrameStart = 0;
        active = true;
    }

    /** GameRenderer.render entered */
    public static void frameBegin() {
        if (!active) return;
        long now = System.nanoTime();
        // the previous frame's CPU time belongs to the mode it was drawn in
        if (lastFrameStart != 0 && lastFrameMode >= 0) {
            ACC[lastFrameMode][0]++;
            ACC[lastFrameMode][1] += now - lastFrameStart;
        }
        int m = (int) ((now - windowStart) / (long) (MODE_S * 1e9)) % 3;
        if (m != mode) {
            mode = m;
            modeStart = now;
            cutting = m != 0;
            ChunkMultiDraw.invalidateLayerCaches();
        }
        boolean settled = now - modeStart > 500_000_000L;
        lastFrameStart = now;
        lastFrameMode = settled ? mode : -1;
        if (QB[0] == 0) {
            GlTrack.queries(QB);
            GlTrack.queries(QE);
            GlTrack.queries(QRB);
            GlTrack.queries(QRE);
        }
        collect();
        GL33C.glQueryCounter(QB[slot], GL33C.GL_TIMESTAMP);
        QMODE[slot] = settled ? mode : -1;
        QRAY[slot] = false;
    }

    /** GameRenderer.render left */
    public static void frameEnd() {
        if (!active || QB[0] == 0) return;
        GL33C.glQueryCounter(QE[slot], GL33C.GL_TIMESTAMP);
        QUSED[slot] = true;
        slot = (slot + 1) % RING;
        long now = System.nanoTime();
        if (mode >= 0 && !SHOT[mode] && now - modeStart > 3_000_000_000L) {
            SHOT[mode] = true;
            shot(MODES[mode]);
        }
    }

    /** the slots written RING-1 frames ago and earlier whose results are in: read without waiting */
    private static void collect() {
        if (!QUSED[slot]) return;
        int s = slot;
        if (GL15C.glGetQueryObjecti(QE[s], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) return;   // still in flight: drop it
        QUSED[s] = false;
        int m = QMODE[s];
        if (m < 0) return;
        long b = GL33C.glGetQueryObjecti64(QB[s], GL15C.GL_QUERY_RESULT), e = GL33C.glGetQueryObjecti64(QE[s], GL15C.GL_QUERY_RESULT);
        ACC[m][2]++;
        ACC[m][3] += e - b;
        if (QRAY[s]) {
            long rb = GL33C.glGetQueryObjecti64(QRB[s], GL15C.GL_QUERY_RESULT), re = GL33C.glGetQueryObjecti64(QRE[s], GL15C.GL_QUERY_RESULT);
            ACC[m][4]++;
            ACC[m][5] += re - rb;
        }
    }

    /** ChunkMultiDraw: the camera's chunk for this frame's cut */
    public static void camera(double x, double z) {
        camCx = net.minecraft.util.Mth.floor(x) >> 4;
        camCz = net.minecraft.util.Mth.floor(z) >> 4;
    }

    /** ChunkMultiDraw: a section outside the near box (Chebyshev distance in chunks) */
    private static final boolean NO_CUT = Boolean.getBoolean("lab.voxel.stream.noCut");   // diagnostics: vanilla's sections drawn under the GPU's

    public static boolean far(BlockPos origin) {
        if (cutAll) return !NO_CUT && VoxelStream.meshes(origin) && !VoxelStream.vanillaOwns(origin);   // the GPU's opaque layers where it meshes, except where mods need vanilla's
        return Math.max(Math.abs((origin.getX() >> 4) - camCx), Math.abs((origin.getZ() >> 4) - camCz)) > NEAR;
    }

    public static String report() {
        active = false;
        cutting = false;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "voxel lod (near %d chunks, grid %dx%dx%d, %.1f MiB, built in %.2f s, %d solid blocks):",
                NEAR, nx, ny, nz, (double) nx * ny * nz / 1048576.0, buildNanos / 1e9, solid));
        for (int m = 0; m < 3; m++) {
            long[] a = ACC[m];
            double cpu = a[0] == 0 ? 0 : a[1] / 1e6 / a[0], gpu = a[2] == 0 ? 0 : a[3] / 1e6 / a[2], ray = a[4] == 0 ? 0 : a[5] / 1e6 / a[4];
            sb.append(String.format(Locale.ROOT, "%n  %-4s frames %6d: CPU frame %.3f ms (%.0f fps), GPU frame %.3f ms (%d samples)%s",
                    MODES[m], a[0], cpu, cpu == 0 ? 0 : 1000 / cpu, gpu, a[2], m == 2 ? String.format(Locale.ROOT, ", of it the ray pass %.3f ms (%d samples)", ray, a[4]) : ""));
        }
        return sb.toString();
    }

    public static void shotNamed(String name) {
        shot(name);
    }

    private static void shot(String name) {
        try {
            var mc = Minecraft.getInstance();
            var img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
            java.nio.file.Path out = dev.lull.SleepOut.dir(mc.gameDirectory.toPath(), "voxel", System.getProperty("sleep.clientAuto", "lod"))
                    .resolve("lod-" + name + ".png");
            img.writeToFile(out);
            img.close();
        } catch (Exception e) {
            LOG.warn("[sleep/voxel] shot failed: {}", e.toString());
        }
    }

    // ---- the grid ----

    private static int nx, ny, nz, gx0, gy0, gz0, voxTex, coarseTex;
    private static long buildNanos, solid;

    private static void buildGrid() {
        long t0 = System.nanoTime();
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int d = mc.options.getEffectiveRenderDistance();
        int ccx = net.minecraft.util.Mth.floor(cam.x) >> 4, ccz = net.minecraft.util.Mth.floor(cam.z) >> 4;
        nx = nz = (2 * d + 1) * 16;
        ny = level.getHeight();
        gx0 = (ccx - d) * 16;
        gz0 = (ccz - d) * 16;
        gy0 = level.getMinBuildHeight();
        byte[] vox = new byte[nx * ny * nz];
        int cx = nx / 4, cy = ny / 4, cz = nz / 4;
        byte[] coarse = new byte[cx * cy * cz];
        byte[] byState = new byte[Block.BLOCK_STATE_REGISTRY.size()];
        java.util.Arrays.fill(byState, (byte) -1);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        solid = 0;
        for (int qx = -d; qx <= d; qx++)
            for (int qz = -d; qz <= d; qz++) {
                LevelChunk c = level.getChunkSource().getChunk(ccx + qx, ccz + qz, false);
                if (c == null) continue;
                LevelChunkSection[] secs = c.getSections();
                int bx = (qx + d) * 16, bz = (qz + d) * 16;
                for (int si = 0; si < secs.length; si++) {
                    LevelChunkSection sec = secs[si];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    int by = si * 16;
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState st = sec.getBlockState(x, y, z);
                                if (st.isAir()) continue;
                                int id = Block.BLOCK_STATE_REGISTRY.getId(st);
                                byte v = byState[id];
                                if (v == -1) {
                                    mp.set(gx0 + bx + x, gy0 + by + y, gz0 + bz + z);
                                    v = 0;
                                    if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) v = (byte) MapColor.WATER.id;
                                    else if (st.isSolidRender(level, mp) || st.getBlock() instanceof LeavesBlock) v = (byte) Math.max(1, st.getMapColor(level, mp).id);
                                    byState[id] = v;
                                }
                                if (v == 0) continue;
                                int gx = bx + x, gy = by + y, gz = bz + z;
                                vox[gx + nx * (gy + ny * gz)] = v;
                                coarse[(gx >> 2) + cx * ((gy >> 2) + cy * (gz >> 2))] = 1;
                                solid++;
                            }
                }
            }
        voxTex = upload3d(voxTex, vox, nx, ny, nz);
        coarseTex = upload3d(coarseTex, coarse, cx, cy, cz);
        buildNanos = System.nanoTime() - t0;
        LOG.info("[sleep/voxel] grid {}x{}x{} at ({}, {}, {}), {} solid blocks, built in {} ms (max 3D texture {})",
                nx, ny, nz, gx0, gy0, gz0, solid, buildNanos / 1_000_000, GL11C.glGetInteger(GL12C.GL_MAX_3D_TEXTURE_SIZE));
    }

    private static int upload3d(int tex, byte[] data, int w, int h, int d) {
        if (tex == 0) tex = GlTrack.tex(GL11C.glGenTextures());
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        buf.put(data).flip();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 6);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_R8UI, w, h, d, 0, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_BYTE, buf);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        MemoryUtil.memFree(buf);
        return tex;
    }

    // ---- the ray pass ----

    private static int program, vao, uInv, uCam, uNearMin, uNearMax, uDims, uPalette, uFog, uFogRange;
    private static com.mojang.blaze3d.pipeline.TextureTarget depthCopy;
    private static final Matrix4f INV = new Matrix4f();
    private static final float[] MAT = new float[16];

    /** LevelRenderer.renderLevel left: in RAY mode, march the far part where the raster left sky */
    public static void afterLevel(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        if (!active || mode != 2 || voxTex == 0) return;
        if (program == 0 && !compile()) return;
        var mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        boolean timed = QMODE[slot] >= 0;
        if (timed) GL33C.glQueryCounter(QRB[slot], GL33C.GL_TIMESTAMP);
        if (depthCopy == null || depthCopy.width != main.width || depthCopy.height != main.height) {
            if (depthCopy != null) depthCopy.destroyBuffers();
            depthCopy = new com.mojang.blaze3d.pipeline.TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        }
        depthCopy.copyDepthFrom(main);
        main.bindWrite(false);

        var cam = mc.gameRenderer.getMainCamera().getPosition();
        projectionMatrix.mul(frustumMatrix, INV).invert();
        GlStateManager._glUseProgram(program);
        GL20C.glUniformMatrix4fv(uInv, false, INV.get(MAT));
        float camX = (float) (cam.x - gx0), camY = (float) (cam.y - gy0), camZ = (float) (cam.z - gz0);
        GL20C.glUniform3f(uCam, camX, camY, camZ);
        int ccx = (net.minecraft.util.Mth.floor(cam.x) >> 4) * 16 - gx0, ccz = (net.minecraft.util.Mth.floor(cam.z) >> 4) * 16 - gz0;
        GL20C.glUniform2f(uNearMin, ccx - NEAR * 16, ccz - NEAR * 16);
        GL20C.glUniform2f(uNearMax, ccx + (NEAR + 1) * 16, ccz + (NEAR + 1) * 16);
        GL20C.glUniform3f(uDims, nx, ny, nz);
        float[] fog = RenderSystem.getShaderFogColor();
        GL20C.glUniform4f(uFog, fog[0], fog[1], fog[2], fog[3]);
        GL20C.glUniform2f(uFogRange, RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());

        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 6);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, voxTex);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 7);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, coarseTex);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 8);
        GlStateManager._bindTexture(depthCopy.getDepthTextureId());
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();
        GlStateManager._disableCull();
        GlStateManager._glBindVertexArray(vao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        // MC's cached state back where it expects it
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 7);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 6);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();   // its static "last program" forgotten
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._enableCull();
        if (timed) {
            GL33C.glQueryCounter(QRE[slot], GL33C.GL_TIMESTAMP);
            QRAY[slot] = true;
        }
    }

    private static boolean compile() {
        String vs = """
                #version 150
                out vec2 uv;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    uv = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        String fs = """
                #version 150
                uniform usampler3D Vox;
                uniform usampler3D Coarse;
                uniform sampler2D Depth;
                uniform mat4 InvViewProj;
                uniform vec3 Cam;
                uniform vec2 NearMin;
                uniform vec2 NearMax;
                uniform vec3 Dims;
                uniform vec3 Palette[64];
                uniform vec4 FogColorV;
                uniform vec2 FogRange;
                in vec2 uv;
                out vec4 fragColor;

                void main() {
                    if (texture(Depth, uv).r < 1.0) discard;
                    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
                    vec3 dir = normalize(p.xyz / p.w);
                    vec3 s = vec3(dir.x >= 0.0 ? 1.0 : -1.0, dir.y >= 0.0 ? 1.0 : -1.0, dir.z >= 0.0 ? 1.0 : -1.0);
                    vec3 d = s * max(abs(dir), vec3(1e-6));
                    vec3 inv = 1.0 / d;
                    vec3 up = step(0.0, s);   // 1 where the ray goes up an axis
                    // where the ray leaves the near box (horizontally) and the grid
                    float t0 = min(((s.x > 0.0 ? NearMax.x : NearMin.x) - Cam.x) * inv.x, ((s.z > 0.0 ? NearMax.y : NearMin.y) - Cam.z) * inv.z);
                    vec3 tb = (up * Dims - Cam) * inv;
                    float tExit = min(min(tb.x, tb.y), tb.z);
                    if (t0 >= tExit) discard;
                    ivec3 cdims = ivec3(Dims) / 4;
                    float t = t0 + 1e-3;
                    for (int i = 0; i < 256; i++) {
                        if (t >= tExit) break;
                        vec3 pos = Cam + d * t;
                        ivec3 c = ivec3(floor(pos * 0.25));
                        if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, cdims))) break;
                        vec3 cmin = vec3(c) * 4.0;
                        vec3 tc = (cmin + 4.0 * up - Cam) * inv;
                        float tCell = min(min(tc.x, tc.y), tc.z);
                        if (texelFetch(Coarse, c, 0).r != 0u) {
                            ivec3 v = clamp(ivec3(floor(pos)), ivec3(cmin), ivec3(cmin) + 3);
                            vec3 tMax = (vec3(v) + up - Cam) * inv;
                            vec3 tDelta = abs(inv);
                            vec3 te = (vec3(v) + 1.0 - up - Cam) * inv;   // the planes it entered through
                            int axis = te.x > te.y ? (te.x > te.z ? 0 : 2) : (te.y > te.z ? 1 : 2);
                            for (int k = 0; k < 12; k++) {
                                uint m = texelFetch(Vox, v, 0).r;
                                if (m != 0u) {
                                    float shade = axis == 1 ? (s.y < 0.0 ? 1.0 : 0.5) : (axis == 0 ? 0.6 : 0.8);
                                    vec3 col = Palette[int(m)] * shade;
                                    vec3 hp = d * t;
                                    float fd = max(length(hp.xz), abs(hp.y));
                                    float f = clamp((fd - FogRange.x) / max(FogRange.y - FogRange.x, 1.0), 0.0, 1.0);
                                    fragColor = vec4(mix(col, FogColorV.rgb, f), 1.0);
                                    return;
                                }
                                if (tMax.x < tMax.y && tMax.x < tMax.z) { t = tMax.x; tMax.x += tDelta.x; v.x += int(s.x); axis = 0; }
                                else if (tMax.y < tMax.z) { t = tMax.y; tMax.y += tDelta.y; v.y += int(s.y); axis = 1; }
                                else { t = tMax.z; tMax.z += tDelta.z; v.z += int(s.z); axis = 2; }
                                if (any(lessThan(v, ivec3(cmin))) || any(greaterThan(v, ivec3(cmin) + 3))) break;
                            }
                        }
                        t = tCell + 1e-3;
                    }
                    discard;
                }
                """;
        int v = shader(GL20C.GL_VERTEX_SHADER, vs), f = shader(GL20C.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return false;
        int p = GlTrack.prog(GL20C.glCreateProgram());
        GL20C.glAttachShader(p, v);
        GL20C.glAttachShader(p, f);
        GL30C.glBindFragDataLocation(p, 0, "fragColor");
        GL20C.glLinkProgram(p);
        if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
            LOG.error("[sleep/voxel] link failed: {}", GL20C.glGetProgramInfoLog(p));
            return false;
        }
        program = p;
        GlStateManager._glUseProgram(p);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(p, "Vox"), 6);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(p, "Coarse"), 7);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(p, "Depth"), 8);
        uInv = GL20C.glGetUniformLocation(p, "InvViewProj");
        uCam = GL20C.glGetUniformLocation(p, "Cam");
        uNearMin = GL20C.glGetUniformLocation(p, "NearMin");
        uNearMax = GL20C.glGetUniformLocation(p, "NearMax");
        uDims = GL20C.glGetUniformLocation(p, "Dims");
        uPalette = GL20C.glGetUniformLocation(p, "Palette");
        uFog = GL20C.glGetUniformLocation(p, "FogColorV");
        uFogRange = GL20C.glGetUniformLocation(p, "FogRange");
        float[] pal = new float[64 * 3];
        for (int i = 1; i < 64; i++) {
            MapColor mc;
            try {
                mc = MapColor.byId(i);
            } catch (RuntimeException e) {
                continue;
            }
            int c = mc.col;
            pal[i * 3] = (c >> 16 & 255) / 255f;
            pal[i * 3 + 1] = (c >> 8 & 255) / 255f;
            pal[i * 3 + 2] = (c & 255) / 255f;
        }
        GL20C.glUniform3fv(uPalette, pal);
        GlStateManager._glUseProgram(0);
        vao = GlTrack.vao(GL30C.glGenVertexArrays());
        return true;
    }

    private static int shader(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            LOG.error("[sleep/voxel] shader compile failed: {}", GL20C.glGetShaderInfoLog(s));
            return 0;
        }
        return s;
    }
}
