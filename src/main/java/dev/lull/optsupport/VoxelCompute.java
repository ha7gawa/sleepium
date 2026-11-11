package dev.lull.optsupport;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.voxel.compute=true (client, measurement prototype, notes/61): section meshing on the GPU by a compute shader,
 * with vanilla's smooth light and ambient occlusion ported exactly (ModelBlockRenderer.AmbientOcclusionFace, the flat
 * path, NeoForge's per-quad AO opt-out) - how many sections per second, against the CPU's 57-89 on the N100.
 *
 * <p>Universal by construction, nothing per block type: the grid holds local state indices; the library holds each
 * state's quads per render type as its BakedModel gives them (getQuads - vanilla's and every mod's static model path),
 * the per-quad shape flags of ModelBlockRenderer.calculateShape, and each state's own answers (shade brightness, view
 * blocking, light block, solid render, emissive, light emission, the model's AO choice); whether a face is drawn
 * comes from vanilla's Block.shouldRenderFace itself, evaluated once per pair of states present (a two-block world);
 * the AO neighbour tables are read from vanilla's own enums (AdjacencyInfo, AmbientVertexRemap) by reflection; light
 * comes from the client light engine's data layers. Within a section the faces are written in a deterministic order
 * (a prefix sum per work group), each with its 4 corner brightnesses (floats) and 4 lightmap coordinates.
 *
 * <p>Over ClientAuto's window: 4 s of the normal raster (a shot), then every section within the render distance meshed
 * every 2 s (timestamp queries per run), the terrain raster cut and the GPU faces drawn instead with the block atlas
 * and the lightmap (a shot). Not yet: biome tint (a constant), position-random model variants (seed 42), block offsets
 * (plants), fluids, the translucent layer (counted, not drawn), frustum culling of the drawn faces.
 */
public final class VoxelCompute {
    public static final boolean ON = Boolean.getBoolean("lab.voxel.compute");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    private static final int CAPACITY = Integer.getInteger("lab.voxel.compute.capacity", 4 << 20);   // faces
    private static final int QF = 40;   // floats per library quad
    private static final int FACE_UINTS = 8;

    private static boolean active, ready, shotA, shotB;
    private static long windowStart, lastMesh;
    private static int nx, ny, nz, gx0, gy0, gz0, sectionCount, quadCount, stateCount;
    private static long buildNanos, pairNanos;
    private static int gridTex, lightTex, sectionBuf, libBuf, propBuf, pairBuf, aoBuf, quadBuf, outBuf, cmdBuf, meshProg, vao;
    private static int uDims, uStates, uOrigin;
    private static final int RING = 8;
    private static final int[] QB = new int[RING], QE = new int[RING];
    private static final boolean[] QUSED = new boolean[RING];
    private static int qslot;
    private static final List<Double> RUNS = new ArrayList<>();
    private static int lastFaces = -1;
    /** -Dlab.voxel.compute.exact=K: K sections compared with vanilla vertex by vertex after the first run (VoxelExact) */
    private static final int EXACT = Integer.getInteger("lab.voxel.compute.exact", 0);
    private static List<int[]> sectionList = List.of();
    private static final List<BakedQuad> LIB_QUADS = new ArrayList<>();
    private static int[] libLayer = new int[0];
    private static float[] libOffset = new float[0];
    static byte[] lightSnap;
    static short[] gridSnap;
    static List<BlockState> snapStates = List.of();
    static int gridNx, gridNy, gridNz;
    private static long cmdCapacity;
    private static int countsBuf, basesBuf, totalsBuf, quadIndexBuf, drawSolid, drawCutout, scanProg, uPass, uScanSections;
    private static int[] uSolid = new int[5], uCutout = new int[5];
    private static int rangesBuf, variantBuf, entryBuf, offsetBuf, dynamicStates, seedStates, offsetFallbacks;
    /** per offsetting state: the 16 float offsets per axis (x [0..15], y [16..31], z [32..47]), shared with VoxelExact */
    static final List<float[]> OFFSET_TABLES = new ArrayList<>();
    private static boolean exactDone;
    private static String exactReport = "";

    private VoxelCompute() {}

    public static void start() {
        RUNS.clear();
        exactDone = false;
        exactReport = "";
        LIB_QUADS.clear();
        shotA = shotB = false;
        ready = false;
        windowStart = System.nanoTime();
        lastMesh = 0;
        active = true;
        built = false;
        rangesRead = false;
        mode = -1;
        for (long[] a : MODE_ACC) java.util.Arrays.fill(a, 0);
        java.util.Arrays.fill(MQ_USED, false);
        lastFrameStart = 0;
    }

    private static boolean built;
    /** -Dlab.voxel.compute.compare=true: from 6 s, 5 s of the terrain raster as it is and 5 s of the GPU faces in turn */
    private static final boolean COMPARE = Boolean.getBoolean("lab.voxel.compute.compare");
    private static final String[] MODES = {"raster", "gpu"};
    private static int mode = -1, lastFrameMode = -1;
    private static long modeStart, lastFrameStart;
    private static final long[][] MODE_ACC = new long[2][4];   // frames, CPU ns, GPU frames, GPU ns
    private static final int MRING = 8;
    private static final int[] MQB = new int[MRING], MQE = new int[MRING], MQ_MODE = new int[MRING];
    private static final boolean[] MQ_USED = new boolean[MRING];
    private static int mslot;
    private static final boolean[] MODE_SHOT = new boolean[2];

    // ---- per frame (VoxelLodFrameMixin / VoxelLodLevelMixin) ----

    /** GameRenderer.render entered: the frame's mode, its CPU time, a GPU timestamp */
    public static void frameBegin() {
        if (!active || !COMPARE) return;
        long now = System.nanoTime();
        if (lastFrameStart != 0 && lastFrameMode >= 0) {
            MODE_ACC[lastFrameMode][0]++;
            MODE_ACC[lastFrameMode][1] += now - lastFrameStart;
        }
        int m = !rangesRead || now - windowStart < 6_000_000_000L ? -1 : (int) ((now - windowStart - 6_000_000_000L) / 5_000_000_000L) % 2;
        if (m != mode) {
            mode = m;
            modeStart = now;
            boolean cut = m == 1;
            if (VoxelLod.cutAll != cut) {
                VoxelLod.cutAll = VoxelLod.cutting = cut;
                ChunkMultiDraw.invalidateLayerCaches();
            }
        }
        boolean settled = mode >= 0 && now - modeStart > 500_000_000L;
        lastFrameStart = now;
        lastFrameMode = settled ? mode : -1;
        if (MQB[0] == 0) {
            GlTrack.queries(MQB);
            GlTrack.queries(MQE);
        }
        if (MQ_USED[mslot] && GL15C.glGetQueryObjecti(MQE[mslot], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
            MQ_USED[mslot] = false;
            int mm = MQ_MODE[mslot];
            if (mm >= 0) {
                MODE_ACC[mm][2]++;
                MODE_ACC[mm][3] += GL33C.glGetQueryObjecti64(MQE[mslot], GL15C.GL_QUERY_RESULT) - GL33C.glGetQueryObjecti64(MQB[mslot], GL15C.GL_QUERY_RESULT);
            }
        } else MQ_USED[mslot] = false;   // still in flight after a full ring: dropped
        GL33C.glQueryCounter(MQB[mslot], GL33C.GL_TIMESTAMP);
        MQ_MODE[mslot] = lastFrameMode;
    }

    public static void frameEnd() {
        if (!active) return;
        long now = System.nanoTime(), t = now - windowStart;
        if (COMPARE && MQB[0] != 0) {
            GL33C.glQueryCounter(MQE[mslot], GL33C.GL_TIMESTAMP);
            MQ_USED[mslot] = true;
            mslot = (mslot + 1) % MRING;
            if (mode >= 0 && !MODE_SHOT[mode] && now - modeStart > 3_000_000_000L) {
                MODE_SHOT[mode] = true;
                VoxelLod.shotNamed("compute-" + MODES[mode]);
            }
        } else {
            if (!shotA && t > 2_000_000_000L) {
                shotA = true;
                VoxelLod.shotNamed("compute-raster");
            }
            if (!shotB && t > 7_000_000_000L && lastMesh != 0) {
                shotB = true;
                VoxelLod.shotNamed("compute-gpu");
            }
        }
        collect();
    }

    /** renderLevel left: from 4 s the grid and a mesh run (every 2 s unless comparing), the GPU faces drawn when the raster is cut */
    public static void afterLevel(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        if (!active) return;
        long now = System.nanoTime();
        if (now - windowStart < 4_000_000_000L) return;
        if (!built) {   // the grid right before the first run (the light vanilla will be compared with)
            built = true;
            try {
                build();
                ready = compile();
            } catch (RuntimeException | ReflectiveOperationException e) {
                LOG.error("[sleep/voxel] compute setup failed", e);
            }
        }
        if (!ready) return;
        if (!COMPARE && !VoxelLod.cutAll) {
            VoxelLod.cutAll = VoxelLod.cutting = true;
            ChunkMultiDraw.invalidateLayerCaches();
        }
        if (lastMesh == 0 || (!COMPARE && now - lastMesh > 2_000_000_000L)) {
            lastMesh = now;
            mesh();
        }
    }

    /** renderLevel, right after the terrain layers (before entities and translucent): the GPU faces in the raster's place */
    public static void afterTerrain(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        if (active && ready && rangesRead && VoxelLod.cutAll) draw(frustumMatrix, projectionMatrix);
    }

    private static void bindAll(int[][] binds, boolean on) {
        for (int[] b : binds) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, b[0], on ? b[1] : 0);
    }

    /** count per section and layer, place (a prefix sum: exact sizes, a deterministic order), write */
    private static void mesh() {
        if (QB[0] == 0) {
            GlTrack.queries(QB);
            GlTrack.queries(QE);
        }
        GL33C.glQueryCounter(QB[qslot], GL33C.GL_TIMESTAMP);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, gridTex);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 10);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, lightTex);
        int[][] binds = {{3, totalsBuf}, {4, entryBuf}, {5, offsetBuf}, {6, variantBuf}, {7, basesBuf}, {8, aoBuf}, {9, pairBuf}, {10, sectionBuf},
                {11, libBuf}, {12, propBuf}, {13, outBuf}, {14, quadBuf}, {15, countsBuf}};
        bindAll(binds, true);
        GlStateManager._glUseProgram(meshProg);
        GL20C.glUniform3i(uDims, nx, ny, nz);
        GL20C.glUniform1i(uStates, stateCount);
        GL20C.glUniform3i(uOrigin, gx0, gy0, gz0);
        GL20C.glUniform1i(uPass, 0);
        GL43C.glDispatchCompute(sectionCount, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        GlStateManager._glUseProgram(scanProg);
        GL20C.glUniform1i(uScanSections, sectionCount);
        GL43C.glDispatchCompute(1, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        GlStateManager._glUseProgram(meshProg);
        GL20C.glUniform1i(uPass, 2);
        GL43C.glDispatchCompute(sectionCount, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL33C.glQueryCounter(QE[qslot], GL33C.GL_TIMESTAMP);
        QUSED[qslot] = true;
        qslot = (qslot + 1) % RING;
        bindAll(binds, false);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
    }

    /** per section and layer: where its faces start and how many (read once after a run) */
    private static int[] secBase = new int[0], secCount = new int[0];
    private static boolean rangesRead;
    private static final java.util.Map<Long, Integer> SECTION_INDEX = new java.util.HashMap<>();

    private static void collect() {
        for (int s = 0; s < RING; s++) {
            if (!QUSED[s] || GL15C.glGetQueryObjecti(QE[s], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            QUSED[s] = false;
            long b = GL33C.glGetQueryObjecti64(QB[s], GL15C.GL_QUERY_RESULT), e = GL33C.glGetQueryObjecti64(QE[s], GL15C.GL_QUERY_RESULT);
            RUNS.add((e - b) / 1e6);
            if (!rangesRead) {   // read long after the run: no stall
                secBase = new int[sectionCount * 24];
                secCount = new int[sectionCount * 24];
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, basesBuf);
                GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, secBase);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, countsBuf);
                GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, secCount);
                int[] totals = new int[8];
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, totalsBuf);
                GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, totals);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                lastFaces = totals[0] + totals[1] + totals[2] + totals[3];
                layerTotals = java.util.Arrays.copyOf(totals, 4);
                rangesRead = true;
            }
            if (EXACT > 0 && !exactDone) {   // the first finished run: compare with vanilla (a read back, long after the run)
                exactDone = true;
                try {
                    int[] ranges = new int[sectionCount * 2];
                    for (int si = 0; si < sectionCount; si++)
                        for (int bk = 0; bk < 24; bk++) ranges[si * 2 + 1] += secCount[si * 24 + bk];
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, outBuf);
                    exactReport = "the mesh exactness test (VoxelExact) is 1.21.1-only";
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                } catch (RuntimeException ex) {
                    GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
                    exactReport = "voxel exact failed: " + ex;
                    LOG.error("[sleep/voxel] exact failed", ex);
                }
                LOG.info("[sleep/voxel] {}", exactReport);
            }
        }
    }

    private static int[] layerTotals = new int[4];
    private static final Matrix4f VP = new Matrix4f();
    private static final float[] MAT = new float[16];
    private static final int MAX_QUADS_PER_DRAW = 32768;
    private static int[] cmds = new int[0];
    private static int samplerMip, samplerFlat;
    private static IntBuffer cmdStage;
    private static long drawnFaces, drawFrames, drawCommands;

    /** the visible sections' faces, per layer one multi-draw (as ChunkMultiDraw draws the raster) */
    private static void draw(Matrix4f frustumMatrix, Matrix4f projectionMatrix) {
        var mc = Minecraft.getInstance();
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        var visible = dev.lull.optsupport.VoxelStream.visibleChunks(mc);
        int need = (visible.size() + 8) * 3 * 6 * 5 * 2;
        if (cmds.length < need) cmds = new int[need];
        int[] start = new int[3], count = new int[3];
        int n = 0;
        for (int layer = 0; layer < 3; layer++) {
            start[layer] = n;
            for (int i = 0, sz = visible.size(); i < sz; i++) {
                var o = visible.get(i).getOrigin();
                Integer si = SECTION_INDEX.get(SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4));
                if (si == null) continue;
                for (int d = 0; d < 6; d++) {
                // the direction's faces can face the camera (a +X face lies at x >= the section's min x, ...)
                boolean facing = switch (d) {
                    case 0 -> cam.y < o.getY() + 16;
                    case 1 -> cam.y > o.getY();
                    case 2 -> cam.z < o.getZ() + 16;
                    case 3 -> cam.z > o.getZ();
                    case 4 -> cam.x < o.getX() + 16;
                    default -> cam.x > o.getX();
                };
                if (!facing) continue;
                int bk = si * 24 + layer * 6 + d;
                int c = secCount[bk], b = secBase[bk];
                while (c > 0) {
                    int take = Math.min(c, MAX_QUADS_PER_DRAW);
                    if ((n + 1) * 5 > cmds.length) cmds = java.util.Arrays.copyOf(cmds, cmds.length * 2);
                    cmds[n * 5] = take * 6;   // DrawElementsIndirectCommand: count, instances, firstIndex, baseVertex, baseInstance
                    cmds[n * 5 + 1] = 1;
                    cmds[n * 5 + 2] = 0;
                    cmds[n * 5 + 3] = b * 4;
                    cmds[n * 5 + 4] = 0;
                    drawnFaces += take;
                    n++;
                    b += take;
                    c -= take;
                }
                }
            }
            count[layer] = n - start[layer];
        }
        drawFrames++;
        drawCommands += n;
        if (n == 0) return;
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
        if ((long) n * 20 > cmdCapacity) {   // grown: a new immutable buffer (Intel refuses indirect draws from a re-specified one)
            GlTrack.delBuf(cmdBuf);
            cmdBuf = GlTrack.buf(GL15C.glGenBuffers());
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
            cmdCapacity = (long) n * 40;
            GL44C.glBufferStorage(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdCapacity, GL44C.GL_DYNAMIC_STORAGE_BIT);
        }
        if (cmdStage == null || cmdStage.capacity() < n * 5) {   // a reused direct buffer (LWJGL takes no heap buffers)
            if (cmdStage != null) MemoryUtil.memFree(cmdStage);
            cmdStage = MemoryUtil.memAllocInt(n * 10);
        }
        cmdStage.clear();
        cmdStage.put(cmds, 0, n * 5).flip();
        GL15C.glBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER, 0, cmdStage);
        projectionMatrix.mul(frustumMatrix, VP);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        int lightmap = RenderSystem.getShaderTexture(2);
        float[] fog = RenderSystem.getShaderFogColor();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 2);
        GlStateManager._bindTexture(lightmap);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 13, outBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 14, quadBuf);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, offsetBuf);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._disableBlend();
        GlStateManager._enableCull();
        GlStateManager._glBindVertexArray(vao);
        for (int layer = 0; layer < 3; layer++) {
            if (count[layer] == 0) continue;
            int prog = layer == 0 ? drawSolid : drawCutout;
            int[] u = layer == 0 ? uSolid : uCutout;
            GlStateManager._glUseProgram(prog);
            if (samplerMip == 0) {   // the atlas sampled as vanilla's render types do
                samplerMip = GlTrack.sampler(org.lwjgl.opengl.GL33C.glGenSamplers());
                org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerMip, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_LINEAR);
                org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerMip, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                samplerFlat = GlTrack.sampler(org.lwjgl.opengl.GL33C.glGenSamplers());
                org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerFlat, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                org.lwjgl.opengl.GL33C.glSamplerParameteri(samplerFlat, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            }
            org.lwjgl.opengl.GL33C.glBindSampler(0, layer == 2 ? samplerFlat : samplerMip);
            GL20C.glUniformMatrix4fv(u[0], false, VP.get(MAT));
            GL20C.glUniform3f(u[1], (float) (cam.x - gx0), (float) (cam.y - gy0), (float) (cam.z - gz0));
            GL20C.glUniform4f(u[2], fog[0], fog[1], fog[2], fog[3]);
            GL20C.glUniform2f(u[3], RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            if (layer > 0) GL20C.glUniform1f(u[4], layer == 1 ? 0.5f : 0.1f);
            GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, (long) start[layer] * 20, count[layer], 20);
        }
        org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 13, 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 14, 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, 0);
        GlStateManager._glUseProgram(0);
        net.minecraft.client.renderer.GameRenderer.getPositionShader().clear();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    public static String report() {
        active = false;
        VoxelLod.cutAll = VoxelLod.cutting = false;
        double mean = RUNS.stream().mapToDouble(Double::doubleValue).skip(1).average().orElse(RUNS.isEmpty() ? 0 : RUNS.get(0));
        return String.format(Locale.ROOT,
                "voxel compute: grid %dx%dx%d, %d non-empty sections, %d states with %d quads in the library (seed-dependent outside vanilla's weighted model %d, own seed %d, unknown offset %d), built in %.2f s (face pairs %.2f s); %d mesh runs, first %.3f ms, mean of the rest %.3f ms GPU -> %.0f sections/s; %d faces (capacity %d), %.1f MiB written per run; runs: %s",
                nx, ny, nz, sectionCount, stateCount, quadCount, dynamicStates, seedStates, offsetFallbacks, buildNanos / 1e9, pairNanos / 1e9, RUNS.size(), RUNS.isEmpty() ? 0 : RUNS.get(0), mean,
                mean == 0 ? 0 : sectionCount / (mean / 1000), lastFaces, CAPACITY, lastFaces * FACE_UINTS * 4.0 / 1048576, RUNS)
                + (exactReport.isEmpty() ? "" : "\n  " + exactReport) + modeReport();
    }

    private static String modeReport() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%n  layers: solid %d, cutout_mipped %d, cutout %d, translucent %d faces; drawn %.0f faces and %.0f commands per frame",
                layerTotals[0], layerTotals[1], layerTotals[2], layerTotals[3], drawFrames == 0 ? 0 : (double) drawnFaces / drawFrames, drawFrames == 0 ? 0 : (double) drawCommands / drawFrames));
        if (!COMPARE) return sb.toString();
        for (int m = 0; m < 2; m++) {
            long[] a = MODE_ACC[m];
            double cpu = a[0] == 0 ? 0 : a[1] / 1e6 / a[0], gpu = a[2] == 0 ? 0 : a[3] / 1e6 / a[2];
            sb.append(String.format(Locale.ROOT, "%n  %-6s frames %6d: CPU frame %.3f ms (%.0f fps), GPU frame %.3f ms (%d samples)", MODES[m], a[0], cpu, cpu == 0 ? 0 : 1000 / cpu, gpu, a[2]));
        }
        return sb.toString();
    }

    // ---- the grid, the light, the sections, the library ----

    /** a world of two blocks, for asking vanilla's Block.shouldRenderFace about a pair of states */
    static final class PairWorld implements BlockGetter {
        BlockState a, b;
        BlockPos posB;

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? a : pos.equals(posB) ? b : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    private static void build() throws ReflectiveOperationException {
        long t0 = System.nanoTime();
        var mc = Minecraft.getInstance();
        var level = mc.level;
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int d = mc.options.getEffectiveRenderDistance();
        int ccx = net.minecraft.util.Mth.floor(cam.x) >> 4, ccz = net.minecraft.util.Mth.floor(cam.z) >> 4;
        int dm = d + 1;   // the grid holds one more ring of chunks: the meshed sections' neighbours (vanilla reads them too)
        nx = nz = (2 * dm + 1) * 16;
        ny = level.getHeight();
        gx0 = (ccx - dm) * 16;
        gz0 = (ccz - dm) * 16;
        gy0 = level.getMinBuildHeight();
        int registry = Block.BLOCK_STATE_REGISTRY.size();
        int[] local = new int[registry];   // global state id -> local index + 1 (0: not seen)
        List<BlockState> states = new ArrayList<>();
        states.add(Blocks.AIR.defaultBlockState());   // local 0: air
        local[Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())] = 1;
        ByteBuffer grid = MemoryUtil.memAlloc(nx * ny * nz * 2);
        MemoryUtil.memSet(grid, 0);
        ByteBuffer light = MemoryUtil.memAlloc(nx * ny * nz);
        MemoryUtil.memSet(light, 0);
        var skyL = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var blockL = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        List<int[]> sections = new ArrayList<>();
        int secCount = level.getSectionsCount(), minSec = level.getMinSection();
        for (int qx = -dm; qx <= dm; qx++)
            for (int qz = -dm; qz <= dm; qz++) {
                LevelChunk c = level.getChunkSource().getChunk(ccx + qx, ccz + qz, false);
                if (c == null) continue;
                LevelChunkSection[] secs = c.getSections();
                int bx = (qx + dm) * 16, bz = (qz + dm) * 16;
                boolean meshed = Math.abs(qx) <= d && Math.abs(qz) <= d;
                for (int si = 0; si < secCount; si++) {
                    int by = si * 16;
                    // light for every section (air too: neighbours of solid blocks read it)
                    SectionPos sp = SectionPos.of(ccx + qx, minSec + si, ccz + qz);
                    DataLayer sky = skyL.getDataLayerData(sp), blk = blockL.getDataLayerData(sp);
                    int[] column = null;   // no sky layer: the light engine's answer is the same all down a column
                    if (sky == null) {
                        column = new int[256];
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) column[z * 16 + x] = skyL.getLightValue(mp.set(gx0 + bx + x, gy0 + by + 15, gz0 + bz + z));
                    }
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                int s, b;
                                s = sky != null ? sky.get(x, y, z) : column[z * 16 + x];
                                b = blk != null ? blk.get(x, y, z) : 0;
                                if ((s | b) != 0) light.put((bx + x) + nx * ((by + y) + ny * (bz + z)), (byte) (s << 4 | b));
                            }
                    LevelChunkSection sec = secs[si];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    if (meshed) sections.add(new int[] {bx, by, bz, 0});
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState st = sec.getBlockState(x, y, z);
                                if (st.isAir()) continue;
                                int id = Block.BLOCK_STATE_REGISTRY.getId(st);
                                if (local[id] == 0) {
                                    states.add(st);
                                    local[id] = states.size();
                                }
                                grid.putShort(2 * ((bx + x) + nx * ((by + y) + ny * (bz + z))), (short) (local[id] - 1));
                            }
                }
            }
        sectionCount = sections.size();
        sectionList = sections;
        gridNx = nx;
        gridNy = ny;
        gridNz = nz;
        stateCount = states.size();
        gridTex = upload3d(grid, GL30C.GL_R16UI, GL11C.GL_UNSIGNED_SHORT, 2);
        if (EXACT > 0) {
            gridSnap = new short[grid.remaining() / 2];
            grid.asShortBuffer().get(0, gridSnap);
            snapStates = states;
        }
        lightTex = upload3d(light, GL30C.GL_R8UI, GL11C.GL_UNSIGNED_BYTE, 1);
        if (EXACT > 0) {   // VoxelExact asks whether light changed since this snapshot
            lightSnap = new byte[light.remaining()];
            light.get(0, lightSnap);
        }
        MemoryUtil.memFree(grid);
        MemoryUtil.memFree(light);

        IntBuffer sb = MemoryUtil.memAllocInt(sectionCount * 4);
        for (int[] s : sections) sb.put(s);
        sb.flip();
        sectionBuf = storage(sb);
        MemoryUtil.memFree(sb);

        // per state: its own answers
        boolean smooth = Minecraft.useAmbientOcclusion();
        IntBuffer props = MemoryUtil.memAllocInt(stateCount * 2);
        for (int i = 0; i < stateCount; i++) {
            BlockState st = states.get(i);
            mp.set(0, 64, 0);
            int f = 0;
            if (st.isViewBlocking(level, mp) && st.getLightBlock(level, mp) != 0) f |= 1;   // closes an AO corner
            if (st.isSolidRender(level, mp)) f |= 2;
            if (st.emissiveRendering(level, mp)) f |= 4;
            f |= (st.getLightEmission(level, mp) & 15) << 8;
            props.put(i * 2, f);
            props.put(i * 2 + 1, Float.floatToRawIntBits(st.getShadeBrightness(level, mp)));
        }
        propBuf = storage(props);
        MemoryUtil.memFree(props);

        // per pair of present states and side: does vanilla draw the face (Block.shouldRenderFace)
        long tp = System.nanoTime();
        PairWorld pw = new PairWorld();
        int bits = stateCount * stateCount * 6;
        IntBuffer pairs = MemoryUtil.memAllocInt((bits + 31) / 32);
        MemoryUtil.memSet(pairs, 0);
        for (int a = 0; a < stateCount; a++) {
            pw.a = states.get(a);
            for (Direction dir : Direction.values()) {
                pw.posB = BlockPos.ZERO.relative(dir);
                for (int b = 0; b < stateCount; b++) {
                    pw.b = states.get(b);
                    if (Block.shouldRenderFace(pw.a, pw, BlockPos.ZERO, dir, pw.posB)) {
                        int bit = (a * stateCount + b) * 6 + dir.get3DDataValue();
                        pairs.put(bit >>> 5, pairs.get(bit >>> 5) | 1 << (bit & 31));
                    }
                }
            }
        }
        pairBuf = storage(pairs);
        MemoryUtil.memFree(pairs);
        pairNanos = System.nanoTime() - tp;

        // vanilla's AO tables, read from its own enums: per face corners[4], remap[4], weights[4][8] (SizeInfo.shape)
        aoBuf = storage(aoTables());

        // the library, in vanilla's emission order: per state its entries, per entry its variants, per variant its
        // quads. A model whose quads do not depend on the seed: one entry, one variant (per render type, per side its
        // quads). A vanilla WeightedBakedModel (every mod's blockstate JSON variants): one entry, its weighted variants,
        // picked from the position's seed. A vanilla MultiPartBakedModel with weighted parts: an entry per render type,
        // side and selected part, each weighted part picked from the seed MultiPartBakedModel hands its parts.
        var br = mc.getBlockRenderer();
        RandomSource rnd = RandomSource.create();
        Direction[] sides = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
        IntBuffer lib = MemoryUtil.memAllocInt(stateCount * 4);
        List<int[]> entries = new ArrayList<>();
        List<int[]> variants = new ArrayList<>();
        OFFSET_TABLES.clear();
        List<float[]> quads = new ArrayList<>();
        dynamicStates = seedStates = offsetFallbacks = 0;
        java.lang.reflect.Field wlist = net.minecraft.client.resources.model.WeightedBakedModel.class.getDeclaredField(dev.lull.Names.field("net/minecraft/client/resources/model/WeightedBakedModel", "list"));
        wlist.setAccessible(true);
        java.lang.reflect.Field mselectors = net.minecraft.client.resources.model.MultiPartBakedModel.class.getDeclaredField(dev.lull.Names.field("net/minecraft/client/resources/model/MultiPartBakedModel", "selectors"));
        mselectors.setAccessible(true);
        for (int i = 0; i < stateCount; i++) {
            BlockState st = states.get(i);
            int eStart = entries.size(), offIdx = 0, seedOff = 512 | 2 | 2 << 3 | 2 << 6;
            if (i > 0 && st.getRenderShape() == RenderShape.MODEL) {
                var model = br.getBlockModel(st);
                mp.set(0, 64, 0);
                boolean collisionFull = st.isCollisionShapeFullBlock(level, mp);
                seedOff = seedOffset(st);
                if (seedOff == 0) {
                    seedStates++;
                    seedOff = 512 | 2 | 2 << 3 | 2 << 6;
                    LOG.info("[sleep/voxel] compute: {} seeds its model some other way", st);
                } else if ((seedOff & 511) != (2 | 2 << 3 | 2 << 6)) LOG.info("[sleep/voxel] compute: {} seeds from offset {}", st, seedOff & 511);
                offIdx = offsetTable(st, level);
                boolean dependent = seedDependent(model, st, rnd, sides);
                if (dependent && model instanceof net.minecraft.client.resources.model.MultiPartBakedModel mpm) {
                    var selectors = (List<?>) mselectors.get(mpm);
                    java.util.BitSet selected = mpm.getSelectors(st);
                    rnd.setSeed(42);
                    for (RenderType rt : model.getRenderTypes(st, rnd, ModelData.EMPTY)) {
                        int layer = layerOf(rt);
                        boolean ao = aoOf(model, st, rt, smooth, level, mp);
                        for (Direction side : sides)
                            for (int j = 0; j < selected.length(); j++) {
                                if (!selected.get(j)) continue;
                                var part = (net.minecraft.client.resources.model.BakedModel) ((org.apache.commons.lang3.tuple.Pair<?, ?>) selectors.get(j)).getRight();
                                rnd.setSeed(42);
                                if (!part.getRenderTypes(st, rnd, ModelData.EMPTY).contains(rt)) continue;
                                int vStart = variants.size(), total = 0, mode = 1;
                                List<net.minecraft.client.resources.model.BakedModel> pv = new ArrayList<>();
                                List<Integer> pws = new ArrayList<>();
                                if (part instanceof net.minecraft.client.resources.model.WeightedBakedModel && seedDependent(part, st, rnd, sides)) {
                                    for (Object o : (List<?>) wlist.get(part)) {
                                        var w = (net.minecraft.util.random.WeightedEntry.Wrapper<?>) o;
                                        pv.add((net.minecraft.client.resources.model.BakedModel) w.getData());
                                        pws.add(w.getWeight().asInt());
                                    }
                                } else {
                                    if (seedDependent(part, st, rnd, sides)) {
                                        dynamicStates++;
                                        LOG.info("[sleep/voxel] compute: {} part {} ({}) changes with the seed some other way", st, j, part.getClass().getName());
                                    }
                                    pv.add(part);
                                    pws.add(1);
                                    mode = 0;
                                }
                                for (int v = 0; v < pv.size(); v++) {
                                    int qs = quads.size();
                                    rnd.setSeed(42);
                                    for (BakedQuad q : pv.get(v).getQuads(st, side, rnd, ModelData.EMPTY, rt)) addQuad(quads, q, side, layer, ao, collisionFull, offIdx);
                                    variants.add(new int[] {qs, quads.size() - qs, pws.get(v), 0});
                                    total += pws.get(v);
                                }
                                entries.add(new int[] {vStart, variants.size() - vStart, total, mode});
                            }
                    }
                } else {
                    List<net.minecraft.client.resources.model.BakedModel> vm = new ArrayList<>();
                    List<Integer> vw = new ArrayList<>();
                    if (dependent && model instanceof net.minecraft.client.resources.model.WeightedBakedModel) {
                        for (Object o : (List<?>) wlist.get(model)) {
                            var w = (net.minecraft.util.random.WeightedEntry.Wrapper<?>) o;
                            vm.add((net.minecraft.client.resources.model.BakedModel) w.getData());
                            vw.add(w.getWeight().asInt());
                        }
                    } else {
                        if (dependent) {
                            dynamicStates++;   // its quads change with the seed some other way: seed 42 for now
                            LOG.info("[sleep/voxel] compute: {} ({}) changes with the seed outside a weighted model", st, model.getClass().getName());
                        }
                        vm.add(model);
                        vw.add(1);
                    }
                    int vStart = variants.size(), total = 0;
                    for (int v = 0; v < vm.size(); v++) {
                        var m = vm.get(v);
                        int qs = quads.size();
                        rnd.setSeed(42);
                        for (RenderType rt : m.getRenderTypes(st, rnd, ModelData.EMPTY)) {
                            int layer = layerOf(rt);
                            boolean ao = aoOf(model, st, rt, smooth, level, mp);
                            for (Direction side : sides) {
                                rnd.setSeed(42);
                                for (BakedQuad q : m.getQuads(st, side, rnd, ModelData.EMPTY, rt)) addQuad(quads, q, side, layer, ao, collisionFull, offIdx);
                            }
                        }
                        variants.add(new int[] {qs, quads.size() - qs, vw.get(v), 0});
                        total += vw.get(v);
                    }
                    entries.add(new int[] {vStart, variants.size() - vStart, total, 0});
                }
            }
            lib.put(i * 4, eStart);
            lib.put(i * 4 + 1, entries.size() - eStart);
            lib.put(i * 4 + 2, offIdx | seedOff << 16);
            lib.put(i * 4 + 3, 0);
        }
        IntBuffer eb = MemoryUtil.memAllocInt(Math.max(1, entries.size()) * 4);
        for (int[] e : entries) eb.put(e);
        if (entries.isEmpty()) eb.put(new int[4]);
        eb.flip();
        entryBuf = storage(eb);
        MemoryUtil.memFree(eb);
        quadCount = quads.size();
        libLayer = new int[quadCount];
        for (int i = 0; i < quadCount; i++) libLayer[i] = (int) quads.get(i)[23];
        libOffset = new float[quadCount];
        for (int i = 0; i < quadCount; i++) libOffset[i] = quads.get(i)[37];
        IntBuffer vb = MemoryUtil.memAllocInt(Math.max(1, variants.size()) * 4);
        for (int[] v : variants) vb.put(v);
        vb.flip();
        variantBuf = storage(vb);
        MemoryUtil.memFree(vb);
        FloatBuffer ob = MemoryUtil.memAllocFloat(Math.max(1, OFFSET_TABLES.size()) * 48);
        for (float[] o : OFFSET_TABLES) ob.put(o);
        if (OFFSET_TABLES.isEmpty()) ob.put(new float[48]);
        ob.flip();
        offsetBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, offsetBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, ob, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        MemoryUtil.memFree(ob);
        libBuf = storage(lib);
        rangesBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, rangesBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, 8L * Math.max(1, sectionCount), 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        MemoryUtil.memFree(lib);
        FloatBuffer qb = MemoryUtil.memAllocFloat(Math.max(1, quadCount) * QF);
        for (float[] f : quads) qb.put(f);
        qb.flip();
        quadBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, quadBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, qb, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        MemoryUtil.memFree(qb);

        SECTION_INDEX.clear();
        for (int i = 0; i < sectionCount; i++) {
            int[] sct = sections.get(i);
            SECTION_INDEX.put(SectionPos.asLong((gx0 + sct[0]) >> 4, (gy0 + sct[1]) >> 4, (gz0 + sct[2]) >> 4), i);
        }
        countsBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, countsBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, 96L * Math.max(1, sectionCount), 0);
        basesBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, basesBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, 96L * Math.max(1, sectionCount), 0);
        totalsBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, totalsBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, 32L, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        outBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, outBuf);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, 16L + 4L * FACE_UINTS * CAPACITY, GL44C.GL_DYNAMIC_STORAGE_BIT);
        GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, new int[] {0, CAPACITY, 0, 0});
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        cmdBuf = GlTrack.buf(GL15C.glGenBuffers());   // immutable (Intel refuses indirect draws from a re-specified buffer)
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
        cmdCapacity = 20L * 4096;
        GL44C.glBufferStorage(GL40C.GL_DRAW_INDIRECT_BUFFER, cmdCapacity, GL44C.GL_DYNAMIC_STORAGE_BIT);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        buildNanos = System.nanoTime() - t0;
        LOG.info("[sleep/voxel] compute grid {}x{}x{}, {} sections, {} states, {} quads, built in {} ms (face pairs {} ms)",
                nx, ny, nz, sectionCount, stateCount, quadCount, buildNanos / 1_000_000, pairNanos / 1_000_000);
    }

    /**
     * A library quad: positions [0..11], UVs [12..19], tint [20], direction [21] (3D data value), flags [22] (1 shade,
     * 2 AO path, 4 shape flag 0: offset face, 8 shape flag 1: smaller than the block, 16 NeoForge AO opt-out reverted:
     * quad.hasAmbientOcclusion), layer [23], cull side [24] (6: none), shape [25..36] (calculateShape's 12 values).
     */
    static float[] quadRecord(BakedQuad q, Direction side, int layer, boolean ao, boolean collisionFull) {
        int[] v = q.getVertices();
        int stride = v.length / 4;
        float[] f = new float[QF];
        float minX = 32f, minY = 32f, minZ = 32f, maxX = -32f, maxY = -32f, maxZ = -32f;
        for (int k = 0; k < 4; k++) {
            float x = Float.intBitsToFloat(v[k * stride]), y = Float.intBitsToFloat(v[k * stride + 1]), z = Float.intBitsToFloat(v[k * stride + 2]);
            f[k * 3] = x;
            f[k * 3 + 1] = y;
            f[k * 3 + 2] = z;
            f[12 + k * 2] = Float.intBitsToFloat(v[k * stride + 4]);
            f[13 + k * 2] = Float.intBitsToFloat(v[k * stride + 5]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        Direction dir = q.getDirection();
        // ModelBlockRenderer.calculateShape, the same comparisons
        boolean flag1, flag0;
        switch (dir) {
            case DOWN -> {
                flag1 = minX >= 1.0E-4F || minZ >= 1.0E-4F || maxX <= 0.9999F || maxZ <= 0.9999F;
                flag0 = minY == maxY && (minY < 1.0E-4F || collisionFull);
            }
            case UP -> {
                flag1 = minX >= 1.0E-4F || minZ >= 1.0E-4F || maxX <= 0.9999F || maxZ <= 0.9999F;
                flag0 = minY == maxY && (maxY > 0.9999F || collisionFull);
            }
            case NORTH -> {
                flag1 = minX >= 1.0E-4F || minY >= 1.0E-4F || maxX <= 0.9999F || maxY <= 0.9999F;
                flag0 = minZ == maxZ && (minZ < 1.0E-4F || collisionFull);
            }
            case SOUTH -> {
                flag1 = minX >= 1.0E-4F || minY >= 1.0E-4F || maxX <= 0.9999F || maxY <= 0.9999F;
                flag0 = minZ == maxZ && (maxZ > 0.9999F || collisionFull);
            }
            case WEST -> {
                flag1 = minY >= 1.0E-4F || minZ >= 1.0E-4F || maxY <= 0.9999F || maxZ <= 0.9999F;
                flag0 = minX == maxX && (minX < 1.0E-4F || collisionFull);
            }
            default -> {
                flag1 = minY >= 1.0E-4F || minZ >= 1.0E-4F || maxY <= 0.9999F || maxZ <= 0.9999F;
                flag0 = minX == maxX && (maxX > 0.9999F || collisionFull);
            }
        }
        f[20] = q.getTintIndex();
        f[21] = dir.get3DDataValue();
        f[22] = (q.isShade() ? 1 : 0) | (ao ? 2 : 0) | (flag0 ? 4 : 0) | (flag1 ? 8 : 0) | (q.hasAmbientOcclusion() ? 16 : 0);
        f[23] = layer;
        f[24] = side == null ? 6 : side.get3DDataValue();
        // shape[Direction 3D value] = min/max, shape[+6] = 1 - that (calculateShape's order)
        float[] shape = new float[12];
        shape[Direction.WEST.get3DDataValue()] = minX;
        shape[Direction.EAST.get3DDataValue()] = maxX;
        shape[Direction.DOWN.get3DDataValue()] = minY;
        shape[Direction.UP.get3DDataValue()] = maxY;
        shape[Direction.NORTH.get3DDataValue()] = minZ;
        shape[Direction.SOUTH.get3DDataValue()] = maxZ;
        shape[Direction.WEST.get3DDataValue() + 6] = 1.0F - minX;
        shape[Direction.EAST.get3DDataValue() + 6] = 1.0F - maxX;
        shape[Direction.DOWN.get3DDataValue() + 6] = 1.0F - minY;
        shape[Direction.UP.get3DDataValue() + 6] = 1.0F - maxY;
        shape[Direction.NORTH.get3DDataValue() + 6] = 1.0F - minZ;
        shape[Direction.SOUTH.get3DDataValue() + 6] = 1.0F - maxZ;
        System.arraycopy(shape, 0, f, 25, 12);
        return f;
    }

    static int layerOf(RenderType rt) {
        return rt == RenderType.solid() ? 0 : rt == RenderType.cutoutMipped() ? 1 : rt == RenderType.cutout() ? 2 : 3;
    }

    static boolean aoOf(net.minecraft.client.resources.model.BakedModel model, BlockState st, RenderType rt, boolean smooth,
            net.minecraft.world.level.Level level, BlockPos pos) {
        return smooth && model.useAmbientOcclusion(st, rt);   // 1.20.1 (Forge): a boolean, the emission rule inside
    }

    private static void addQuad(List<float[]> quads, BakedQuad q, Direction side, int layer, boolean ao, boolean collisionFull, int offIdx) {
        float[] rec = quadRecord(q, side, layer, ao, collisionFull);
        rec[37] = offIdx;
        quads.add(rec);
        LIB_QUADS.add(q);
    }

    private static final long[] PROBE_SEEDS = {42L, net.minecraft.util.Mth.getSeed(1, 2, 3), net.minecraft.util.Mth.getSeed(-77, 64, 301),
            net.minecraft.util.Mth.getSeed(1000, -5, -999), net.minecraft.util.Mth.getSeed(17, 80, -3), net.minecraft.util.Mth.getSeed(-4000, 12, 77),
            net.minecraft.util.Mth.getSeed(5, 5, 5), net.minecraft.util.Mth.getSeed(123456, 70, -654321)};

    /** do the model's quads (any render type, any side) change with the random seed? (the same quad objects = unchanged) */
    static boolean seedDependent(net.minecraft.client.resources.model.BakedModel model, BlockState st, RandomSource rnd, Direction[] sides) {
        List<BakedQuad> first = null;
        for (long seed : PROBE_SEEDS) {
            List<BakedQuad> all = new ArrayList<>();
            rnd.setSeed(seed);
            for (RenderType rt : model.getRenderTypes(st, rnd, ModelData.EMPTY))
                for (Direction side : sides) {
                    rnd.setSeed(seed);
                    all.addAll(model.getQuads(st, side, rnd, ModelData.EMPTY, rt));
                }
            if (first == null) first = all;
            else if (all.size() != first.size()) return true;
            else for (int k = 0; k < all.size(); k++) if (all.get(k) != first.get(k)) return true;
        }
        return false;
    }

    /**
     * How the state seeds its model, observed: the offset d (within 2 blocks) with getSeed(p) == Mth.getSeed(p + d) at
     * every probe (vanilla's default is 0; a double plant's upper half seeds from the half below), packed as
     * (dx+2) | (dy+2) << 3 | (dz+2) << 6, plus 512 when found; 0 when no such offset explains it.
     */
    static int seedOffset(BlockState st) {
        int[][] ps = {{1, 2, 3}, {-77, 64, 301}, {1000, -5, -999}, {17, 80, -3}, {-3, -40, 9}};
        for (int dy = 0; dy <= 2; dy = dy <= 0 ? 1 - dy : -dy)   // 0, 1, -1, 2, -2 first: the likely ones
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    boolean all = true;
                    for (int[] p : ps)
                        if (st.getSeed(new BlockPos(p[0], p[1], p[2])) != net.minecraft.util.Mth.getSeed(p[0] + dx, p[1] + dy, p[2] + dz)) {
                            all = false;
                            break;
                        }
                    if (all) return 512 | (dx + 2) | (dy + 2) << 3 | (dz + 2) << 6;
                    if (dy == -2 && dx == 2 && dz == 2) return 0;
                }
        return 0;
    }

    /**
     * The state's offset (getOffset) observed, nothing assumed about its function: none -> 0; else the values seen at
     * many positions are filed under the 4-bit fields of Mth.getSeed(x, 0, z) that vanilla's offset functions read
     * (x: bits 0-3, y: bits 4-7, z: bits 8-11) -> the table (16 floats per axis, as PoseStack.translate takes them)
     * and its index + 1, if every observation agrees with it (and the height does not matter); anything else is
     * counted and left without an offset.
     */
    private static int offsetTable(BlockState st, net.minecraft.world.level.Level level) {
        float[] t = new float[48];
        boolean[] seen = new boolean[48];
        boolean any = false;
        List<float[]> obs = new ArrayList<>();
        for (int x = -40; x < 40; x += 3)
            for (int z = -40; z < 40; z += 3) {
                int y = 64 + ((x * 7 + z * 13) & 31);
                var o = st.getOffset(level, new BlockPos(x, y, z));
                long i = net.minecraft.util.Mth.getSeed(x, 0, z);
                float ox = (float) o.x, oy = (float) o.y, oz = (float) o.z;
                if (ox != 0 || oy != 0 || oz != 0) any = true;
                obs.add(new float[] {(int) (i & 15L), (int) (i >> 4 & 15L), (int) (i >> 8 & 15L), ox, oy, oz});
            }
        if (!any) return 0;
        for (float[] o : obs)
            for (int axis = 0; axis < 3; axis++) {
                int slot = axis * 16 + (int) o[axis];
                if (!seen[slot]) {
                    seen[slot] = true;
                    t[slot] = o[3 + axis];
                }
            }
        for (float[] o : obs)
            if (t[(int) o[0]] != o[3] || t[16 + (int) o[1]] != o[4] || t[32 + (int) o[2]] != o[5]) {
                offsetFallbacks++;
                return 0;
            }
        OFFSET_TABLES.add(t);
        return OFFSET_TABLES.size();
    }

    static IntBuffer aoTables() throws ReflectiveOperationException {
        Class<?> adj = Class.forName("net.minecraft.client.renderer.block.ModelBlockRenderer$AdjacencyInfo");
        Class<?> remap = Class.forName("net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientVertexRemap");
        Class<?> size = Class.forName("net.minecraft.client.renderer.block.ModelBlockRenderer$SizeInfo");
        var fromA = adj.getDeclaredMethod(dev.lull.Names.method("net/minecraft/client/renderer/block/ModelBlockRenderer$AdjacencyInfo", "fromFacing", "(Lnet/minecraft/core/Direction;)Lnet/minecraft/client/renderer/block/ModelBlockRenderer$AdjacencyInfo;"), Direction.class);
        var fromR = remap.getDeclaredMethod(dev.lull.Names.method("net/minecraft/client/renderer/block/ModelBlockRenderer$AmbientVertexRemap", "fromFacing", "(Lnet/minecraft/core/Direction;)Lnet/minecraft/client/renderer/block/ModelBlockRenderer$AmbientVertexRemap;"), Direction.class);
        fromA.setAccessible(true);
        fromR.setAccessible(true);
        var corners = adj.getDeclaredField(dev.lull.Names.field("net/minecraft/client/renderer/block/ModelBlockRenderer$AdjacencyInfo", "corners"));
        corners.setAccessible(true);
        var shapeF = size.getDeclaredField(dev.lull.Names.field("net/minecraft/client/renderer/block/ModelBlockRenderer$SizeInfo", "shape"));
        shapeF.setAccessible(true);
        IntBuffer t = MemoryUtil.memAllocInt(6 * 40);
        for (Direction dir : Direction.values()) {
            int base = dir.get3DDataValue() * 40;
            Object a = fromA.invoke(null, dir), r = fromR.invoke(null, dir);
            Direction[] cs = (Direction[]) corners.get(a);
            for (int k = 0; k < 4; k++) t.put(base + k, cs[k].get3DDataValue());
            for (int k = 0; k < 4; k++) {
                var vf = remap.getDeclaredField(dev.lull.Names.field("net/minecraft/client/renderer/block/ModelBlockRenderer$AmbientVertexRemap", "vert" + k));
                vf.setAccessible(true);
                t.put(base + 4 + k, vf.getInt(r));
            }
            for (int k = 0; k < 4; k++) {
                var wf = adj.getDeclaredField(dev.lull.Names.field("net/minecraft/client/renderer/block/ModelBlockRenderer$AdjacencyInfo", "vert" + k + "Weights"));
                wf.setAccessible(true);
                Object[] ws = (Object[]) wf.get(a);
                for (int j = 0; j < 8; j++) t.put(base + 8 + k * 8 + j, shapeF.getInt(ws[j]));
            }
        }
        return t;
    }

    private static int upload3d(ByteBuffer data, int internal, int type, int align) {
        int tex = GlTrack.tex(GL11C.glGenTextures());
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 9);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, tex);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, align);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, internal, nx, ny, nz, 0, GL30C.GL_RED_INTEGER, type, data);
        GlStateManager._pixelStore(GL11C.GL_UNPACK_ALIGNMENT, 4);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        return tex;
    }

    static int storage(IntBuffer data) {
        int b = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, b);
        GL44C.glBufferStorage(GL43C.GL_SHADER_STORAGE_BUFFER, data, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        return b;
    }

    // ---- shaders ----

    /** the grid as one block of the world at Origin: cells are grid coordinates */
    static final String PRELUDE_GRID = """
                bool inside(ivec3 p) { return all(greaterThanEqual(p, ivec3(0))) && all(lessThan(p, Dims)); }
                uint stateAt(ivec3 p) { return inside(p) ? texelFetch(Grid, p, 0).r : 0u; }
                uint lightAt(ivec3 p) { return inside(p) ? texelFetch(Light, p, 0).r : (p.y >= Dims.y ? 0xF0u : 0u); }
                ivec3 worldOf(ivec3 p) { return p + Origin; }
                uint encodeCell(ivec3 p) { return uint(p.x) | (uint(p.y) << 10u) | (uint(p.z) << 20u); }
            """;

    private static boolean compile() {
        String mesh = meshSource(PRELUDE_GRID);
        return compileWith(mesh);
    }

    /** the mesh shader around a prelude (inside, stateAt, lightAt, worldOf, encodeCell) */
    static String meshSource(String prelude) {
        String mesh = """
                #version 430
                layout(local_size_x = 256) in;
                uniform usampler3D Grid;
                uniform usampler3D Light;
                uniform ivec3 Dims;
                uniform int States;
                uniform ivec3 Origin;
                layout(std430, binding = 4) buffer Entries { uvec4 entries[]; };
                layout(std430, binding = 6) buffer Variants { uvec4 variants[]; };
                layout(std430, binding = 7) buffer Bases { uint bases[]; };
                layout(std430, binding = 15) buffer Counts { uint counts4[]; };
                uniform int Pass;   // 0: count per layer, 2: write at the placed bases
                layout(std430, binding = 8) buffer Ao { int ao[]; };
                layout(std430, binding = 9) buffer Pairs { uint pairs[]; };
                layout(std430, binding = 10) buffer Sections { ivec4 sections[]; };
                layout(std430, binding = 11) buffer Lib { uvec4 lib[]; };
                layout(std430, binding = 12) buffer Props { uvec2 props[]; };
                layout(std430, binding = 13) buffer Out { uint counter; uint capacity; uint pad0; uint pad1; uint faces[]; };
                layout(std430, binding = 14) buffer Quads { float quads[]; };
                shared uint counts[6144];   // per invocation (256) and bucket (layer * 6 + direction)
                const ivec3 OFF[6] = ivec3[](ivec3(0, -1, 0), ivec3(0, 1, 0), ivec3(0, 0, -1), ivec3(0, 0, 1), ivec3(-1, 0, 0), ivec3(1, 0, 0));
%PRELUDE%
                #ifndef FACE_STRIDE
                #define FACE_STRIDE 8u
                #endif

                // 64-bit integers as (lo, hi): vanilla's Mth.getSeed and LegacyRandomSource, exactly
                uvec2 i64(int v) { return uvec2(uint(v), v < 0 ? 0xFFFFFFFFu : 0u); }
                uvec2 add64(uvec2 a, uvec2 b) { uint lo = a.x + b.x; return uvec2(lo, a.y + b.y + (lo < a.x ? 1u : 0u)); }
                uvec2 mul64(uvec2 a, uvec2 b) { uint hi, lo; umulExtended(a.x, b.x, hi, lo); return uvec2(lo, hi + a.x * b.y + a.y * b.x); }
                uvec2 mthSeed(int x, int y, int z) {
                    uvec2 i = i64(int(uint(x) * 3129871u)) ^ mul64(i64(z), uvec2(116129781u, 0u)) ^ i64(y);
                    i = add64(mul64(mul64(i, i), uvec2(42317861u, 0u)), mul64(i, uvec2(11u, 0u)));
                    return uvec2((i.x >> 16u) | (i.y << 16u), uint(int(i.y) >> 16));
                }
                int secondNext(uvec2 s) {   // (int) nextLong() after setSeed(s): the second next(32)
                    uvec2 seed = uvec2(s.x ^ 0xDEECE66Du, (s.y ^ 0x5u) & 0xFFFFu);
                    for (int n = 0; n < 2; n++) { seed = add64(mul64(seed, uvec2(0xDEECE66Du, 0x5u)), uvec2(11u, 0u)); seed.y &= 0xFFFFu; }
                    return int((seed.x >> 16u) | (seed.y << 16u));
                }
                // the cell's seeds: the position's (the state's observed offset applied) and the one MultiPartBakedModel
                // hands its parts (k = nextLong() after setSeed(position seed)); the offset nibbles
                struct Seeds { uvec2 pos; uvec2 part; };
                Seeds seeds(ivec3 p, uint s, out uint nib) {
                    uvec4 e = lib[s];
                    ivec3 w = worldOf(p);
                    nib = (e.z & 0xFFFFu) != 0u ? mthSeed(w.x, 0, w.z).x & 0xFFFFFu & 0xFFFu : 0u;
                    uint so = e.z >> 16u;
                    ivec3 sp = w + ivec3(int(so & 7u) - 2, int((so >> 3u) & 7u) - 2, int((so >> 6u) & 7u) - 2);
                    Seeds r;
                    r.pos = mthSeed(sp.x, sp.y, sp.z);
                    // nextLong() = (next(32) << 32) + next(32), both as signed ints
                    uvec2 seed = uvec2(r.pos.x ^ 0xDEECE66Du, (r.pos.y ^ 0x5u) & 0xFFFFu);
                    seed = add64(mul64(seed, uvec2(0xDEECE66Du, 0x5u)), uvec2(11u, 0u)); seed.y &= 0xFFFFu;
                    uint n1 = (seed.x >> 16u) | (seed.y << 16u);
                    seed = add64(mul64(seed, uvec2(0xDEECE66Du, 0x5u)), uvec2(11u, 0u)); seed.y &= 0xFFFFu;
                    uint n2 = (seed.x >> 16u) | (seed.y << 16u);
                    r.part = add64(uvec2(0u, n1), i64(int(n2)));
                    return r;
                }
                // an entry's quads at this cell: WeightedBakedModel.getQuads' pick (from the position's seed, or from
                // the part seed for a multipart part)
                uvec2 pickEntry(uint ei, Seeds sd) {
                    uvec4 en = entries[ei];
                    uint vi = en.x;
                    if (en.y > 1u) {
                        int j = secondNext(en.w == 1u ? sd.part : sd.pos);
                        int idx = j == int(0x80000000u) ? -1 : int(uint(abs(j)) % en.z);
                        for (uint t = 0u; t < en.y; t++) { idx -= int(variants[en.x + t].z); if (idx < 0) { vi = en.x + t; break; } }
                    }
                    return variants[vi].xy;
                }

                uint bucketOf(uint q) { return uint(quads[q * 40u + 23u]) * 6u + uint(quads[q * 40u + 21u]); }

                float shadeB(uint s) { return uintBitsToFloat(props[s].y); }
                bool aoOpen(uint s) { return (props[s].x & 1u) == 0u; }   // !isViewBlocking || getLightBlock == 0
                bool solid(uint s) { return (props[s].x & 2u) != 0u; }
                // LevelRenderer.getLightColor(level, state, pos)
                int lightColor(uint s, ivec3 p) {
                    uint f = props[s].x;
                    if ((f & 4u) != 0u) return 15728880;
                    uint l = lightAt(p);
                    int sky = int(l >> 4u), blk = int(l & 15u), em = int((f >> 8u) & 15u);
                    if (blk < em) blk = em;
                    return sky << 20 | blk << 4;
                }


                #ifdef VERTS
                // ready vertices (16 bytes): x y z (u16, (local + 8) * 2048), cell | tint code << 12, uv (unorm16 pair),
                // brightness | block light << 8 | sky light << 16 (bytes)
                layout(std430, binding = 5) buffer Offsets { float offs[]; };
                layout(std430, binding = 2) buffer Families { float fams[]; };
                layout(std430, binding = 1) buffer FaceSlots { uint faceSlot[]; };
                #ifdef MERGE_STATS
                // -Dlab.voxel.stream.mergeStats (a measurement): which meshed faces a face merge could take (binding 0, the one left on Intel)
                layout(std430, binding = 0) buffer MergeStats { uint mstat[]; };
                #endif   // each face's section slot (the compacted draw)
                void putVert(uint at, uint c, vec3 pos, vec2 uv, float br, uint lu, uint lv, uint cellTint) {
                    if (c == 0u) faceSlot[at] = uint(sections[gl_WorkGroupID.x].w) & 0x1FFFFFFFu;
                    uvec3 pi = uvec3(clamp(round((pos + 8.0) * 2048.0), vec3(0.0), vec3(65535.0)));
                    uvec2 ui = uvec2(clamp(round(uv * 65535.0), vec2(0.0), vec2(65535.0)));
                    uint b = at * FACE_STRIDE + c * 4u;
                    faces[b] = pi.x | pi.y << 16u;
                    faces[b + 1u] = pi.z | cellTint << 16u;
                    faces[b + 2u] = ui.x | ui.y << 16u;
                    faces[b + 3u] = uint(clamp(round(br * 255.0), 0.0, 255.0)) | lu << 8u | lv << 16u;
                }
                uint cellOf(ivec3 p) { ivec3 l = p & 15; return uint(l.x) + 16u * uint(l.y) + 256u * uint(l.z); }
                void quadVerts(uint at, ivec3 p, uint q, uint nib, float br[4], int lm[4]) {
                    uint qb = q * 40u;
                    vec3 off = vec3(0.0);
                    int ot = int(quads[qb + 37u]);
                    if (ot > 0) { uint tb = uint(ot - 1) * 48u; off = vec3(offs[tb + (nib & 15u)], offs[tb + 16u + ((nib >> 4u) & 15u)], offs[tb + 32u + ((nib >> 8u) & 15u)]); }
                    int ti = int(quads[qb + 20u]);
                    uint code = (ti >= 0 && ti < 4) ? uint(ti + 1) : 0u;
                    #ifdef MERGE_STATS
                    {
                        uint fl = uint(quads[qb + 22u]);
                        int cl = int(quads[qb + 24u]);
                        bool eq = br[0] == br[1] && br[1] == br[2] && br[2] == br[3] && lm[0] == lm[1] && lm[1] == lm[2] && lm[2] == lm[3];
                        atomicAdd(mstat[0], 1u);
                        if (eq && (fl & 8u) == 0u && cl <= 5) {
                            atomicAdd(mstat[code == 0u ? 1u : 2u], 1u);
                            if (int(quads[qb + 21u]) == 1) atomicAdd(mstat[code == 0u ? 3u : 4u], 1u);   // up faces
                        }
                    }
                    #endif
                    vec3 base = vec3(p & 15) + off;
                    uint ct = cellOf(p) | code << 12u;
                    for (uint k = 0u; k < 4u; k++)
                        putVert(at, k, base + vec3(quads[qb + k * 3u], quads[qb + k * 3u + 1u], quads[qb + k * 3u + 2u]),
                                vec2(quads[qb + 12u + k * 2u], quads[qb + 13u + k * 2u]), br[k], uint(lm[k] & 255), uint((lm[k] >> 16) & 255), ct);
                }
                float sprU(uint fb, float f) { return fams[fb] + (fams[fb + 1u] - fams[fb]) * f; }
                float sprV(uint fb, float f) { return fams[fb + 2u] + (fams[fb + 3u] - fams[fb + 2u]) * f; }
                // LiquidBlockRenderer's corners and UVs (the record path does the same in the vertex stage)
                void fluidVerts(uint at, ivec3 p, uint w1, float h0, float h1, float h2, float h3, uint lm, float ang) {
                    uint kind = (w1 >> 16u) & 15u, dir = (w1 >> 20u) & 15u, fb = ((w1 >> 24u) & 15u) * 16u, fl = w1 >> 28u;
                    vec3 cs[4];
                    vec2 us[4];
                    float bright;
                    if (kind <= 1u) {
                        float f17, f18, f19, f20, f21, f22, f23, f24;
                        if ((fl & 1u) != 0u) { f17 = sprU(fb, 0.0); f21 = sprV(fb, 0.0); f18 = f17; f22 = sprV(fb, 1.0); f19 = sprU(fb, 1.0); f23 = f22; f20 = f19; f24 = f21; }
                        else {
                            float f26 = sin(ang) * 0.25, f27 = cos(ang) * 0.25;
                            uint ff = fb + 4u;
                            f17 = sprU(ff, 0.5 + (-f27 - f26)); f21 = sprV(ff, 0.5 + -f27 + f26);
                            f18 = sprU(ff, 0.5 + -f27 + f26); f22 = sprV(ff, 0.5 + f27 + f26);
                            f19 = sprU(ff, 0.5 + f27 + f26); f23 = sprV(ff, 0.5 + (f27 - f26));
                            f20 = sprU(ff, 0.5 + (f27 - f26)); f24 = sprV(ff, 0.5 + (-f27 - f26));
                        }
                        float f53 = (f17 + f18 + f19 + f20) / 4.0, f54 = (f21 + f22 + f23 + f24) / 4.0, sh = fams[fb + 12u];
                        f17 += sh * (f53 - f17); f18 += sh * (f53 - f18); f19 += sh * (f53 - f19); f20 += sh * (f53 - f20);
                        f21 += sh * (f54 - f21); f22 += sh * (f54 - f22); f23 += sh * (f54 - f23); f24 += sh * (f54 - f24);
                        if (kind == 0u) {
                            cs[0] = vec3(0.0, h0, 0.0); cs[1] = vec3(0.0, h1, 1.0); cs[2] = vec3(1.0, h2, 1.0); cs[3] = vec3(1.0, h3, 0.0);
                            us[0] = vec2(f17, f21); us[1] = vec2(f18, f22); us[2] = vec2(f19, f23); us[3] = vec2(f20, f24);
                        } else {
                            cs[0] = vec3(0.0, h0, 0.0); cs[1] = vec3(1.0, h3, 0.0); cs[2] = vec3(1.0, h2, 1.0); cs[3] = vec3(0.0, h1, 1.0);
                            us[0] = vec2(f17, f21); us[1] = vec2(f20, f24); us[2] = vec2(f19, f23); us[3] = vec2(f18, f22);
                        }
                        bright = 1.0;
                    } else if (kind == 2u) {
                        cs[0] = vec3(0.0, h0, 1.0); cs[1] = vec3(0.0, h0, 0.0); cs[2] = vec3(1.0, h0, 0.0); cs[3] = vec3(1.0, h0, 1.0);
                        us[0] = vec2(fams[fb], fams[fb + 3u]); us[1] = vec2(fams[fb], fams[fb + 2u]); us[2] = vec2(fams[fb + 1u], fams[fb + 2u]); us[3] = vec2(fams[fb + 1u], fams[fb + 3u]);
                        bright = 0.5;
                    } else {
                        float x47, x51, z49, z52;
                        if (dir == 2u) { x47 = 0.0; x51 = 1.0; z49 = 0.001; z52 = 0.001; }
                        else if (dir == 3u) { x47 = 1.0; x51 = 0.0; z49 = 1.0 - 0.001; z52 = 1.0 - 0.001; }
                        else if (dir == 4u) { x47 = 0.001; x51 = 0.001; z49 = 1.0; z52 = 0.0; }
                        else { x47 = 1.0 - 0.001; x51 = 1.0 - 0.001; z49 = 0.0; z52 = 1.0; }
                        uint sp = (fl & 2u) != 0u ? fb + 8u : fb + 4u;
                        float f56 = sprU(sp, 0.0), f58 = sprU(sp, 0.5), f59 = sprV(sp, (1.0 - h0) * 0.5), f60 = sprV(sp, (1.0 - h1) * 0.5), f31 = sprV(sp, 0.5);
                        if (kind == 3u) {
                            cs[0] = vec3(x47, h0, z49); cs[1] = vec3(x51, h1, z52); cs[2] = vec3(x51, h2, z52); cs[3] = vec3(x47, h2, z49);
                            us[0] = vec2(f56, f59); us[1] = vec2(f58, f60); us[2] = vec2(f58, f31); us[3] = vec2(f56, f31);
                        } else {
                            cs[0] = vec3(x47, h2, z49); cs[1] = vec3(x51, h2, z52); cs[2] = vec3(x51, h1, z52); cs[3] = vec3(x47, h0, z49);
                            us[0] = vec2(f56, f31); us[1] = vec2(f58, f31); us[2] = vec2(f58, f60); us[3] = vec2(f56, f59);
                        }
                        bright = dir < 4u ? 0.8 : 0.6;
                    }
                    uint ct = cellOf(p) | 5u << 12u;
                    vec3 base = vec3(p & 15);
                    for (uint k = 0u; k < 4u; k++) putVert(at, k, base + cs[k], us[k], bright, lm & 255u, (lm >> 8u) & 255u, ct);
                }
                #endif
                #ifdef FLUIDS
                layout(std430, binding = 3) buffer FProps { uvec4 fprops[]; };
                uint fam(uint st) { return fprops[st * 2u].x & 15u; }
                bool anyFluid(uint st) { return (fprops[st * 2u].x & (1u << 22u)) != 0u; }
                float ownH(uint st) { return uintBitsToFloat(fprops[st * 2u].y); }
                bool hides(uint st, uint face, uint F) { return ((fprops[st * 2u].z >> ((F - 1u) * 6u + face)) & 1u) != 0u; }
                bool selfOcc(uint st, uint face) { return ((fprops[st * 2u].x >> (9u + face)) & 1u) != 0u; }
                bool renderFace(uint s, uint face, uint other, uint F) { return !selfOcc(s, face) && !hides(other, face ^ 1u, F); }
                float sideCover(uint st, uint dir) { uvec4 v = fprops[st * 2u + 1u]; return uintBitsToFloat(dir == 2u ? v.x : dir == 3u ? v.y : dir == 4u ? v.z : v.w); }
                // LiquidBlockRenderer.getHeight
                float fh(uint F, ivec3 q) {
                    uint st = stateAt(q);
                    if (fam(st) == F) return fam(stateAt(q + ivec3(0, 1, 0))) == F ? 1.0 : ownH(st);
                    return (fprops[st * 2u].x & 16u) == 0u ? 0.0 : -1.0;
                }
                void addW(inout float a0, inout float a1, float h) {
                    if (h >= 0.8) { a0 += h * 10.0; a1 += 10.0; } else if (h >= 0.0) { a0 += h; a1 += 1.0; }
                }
                float avgH(uint F, float cur, float h1, float h2, ivec3 q) {
                    if (h2 >= 1.0 || h1 >= 1.0) return 1.0;
                    precise float a0 = 0.0, a1 = 0.0;
                    if (h2 > 0.0 || h1 > 0.0) { float f = fh(F, q); if (f >= 1.0) return 1.0; addW(a0, a1, f); }
                    addW(a0, a1, cur); addW(a0, a1, h2); addW(a0, a1, h1);
                    return a0 / a1;
                }
                // FlowingFluid.getFlow's horizontal part (the falling part only adds -Y after normalising: the angle stays)
                vec2 fluidFlow(uint F, uint s, ivec3 p) {
                    float own = ownH(s);
                    vec2 d = vec2(0.0);
                    for (uint k = 2u; k < 6u; k++) {
                        ivec3 q = p + OFF[k];
                        uint st = stateAt(q);
                        if (anyFluid(st) && fam(st) != F) continue;
                        float f = ownH(st), f1 = 0.0;
                        if (f == 0.0) {
                            if ((fprops[st * 2u].x & 32u) == 0u) {
                                uint sb = stateAt(q + ivec3(0, -1, 0));
                                if (!anyFluid(sb) || fam(sb) == F) { f = ownH(sb); if (f > 0.0) f1 = own - (f - 0.8888889); }
                            }
                        } else if (f > 0.0) f1 = own - f;
                        if (f1 != 0.0) d += vec2(float(OFF[k].x), float(OFF[k].z)) * f1;
                    }
                    return d;
                }
                // getLightColor in LiquidBlockRenderer: the greater of the cell's and the one above, per channel
                uint fluidLight(uint sa, ivec3 pa) {
                    int a = lightColor(sa, pa), b = lightColor(stateAt(pa + ivec3(0, 1, 0)), pa + ivec3(0, 1, 0));
                    int l = max(a & 255, b & 255) | max((a >> 16) & 255, (b >> 16) & 255) << 16;
                    return uint(l & 255) | uint((l >> 16) & 255) << 8u;
                }
                void femit(inout uint cnt[24], bool write, uint sec, uint bk, ivec3 p, uint w1, float a, float b, float c, float d, uint lm, float ang) {
                    uint at = cnt[bk]++;
                    if (!write || at >= capacity || at >= bases[sec * 24u + bk] + counts4[sec * 24u + bk]) return;
                    #ifdef VERTS
                    if (bk < 18u) { fluidVerts(at, p, w1, a, b, c, d, lm, ang); return; }
                    #endif
                    uint o = at * FACE_STRIDE;
                    faces[o] = encodeCell(p);
                    faces[o + 1u] = w1;
                    faces[o + 2u] = floatBitsToUint(a); faces[o + 3u] = floatBitsToUint(b); faces[o + 4u] = floatBitsToUint(c); faces[o + 5u] = floatBitsToUint(d);
                    faces[o + 6u] = lm;
                    faces[o + 7u] = floatBitsToUint(ang);
                }
                // LiquidBlockRenderer.tesselate: the faces of one fluid cell, in its order
                void fluidFaces(ivec3 p, uint s, uint sec, inout uint cnt[24], bool write) {
                    uint F = fam(s), x = fprops[s * 2u].x, L = (x & (1u << 15u)) != 0u ? 18u : 0u;
                    uint sD = stateAt(p + OFF[0]), sU = stateAt(p + OFF[1]), sN = stateAt(p + OFF[2]), sS = stateAt(p + OFF[3]), sW = stateAt(p + OFF[4]), sE = stateAt(p + OFF[5]);
                    bool top = !hides(sU, 0u, F);
                    bool bottom = renderFace(s, 0u, sD, F) && (fprops[sD * 2u].x & 128u) == 0u;
                    bool fN = renderFace(s, 2u, sN, F), fS = renderFace(s, 3u, sS, F), fW = renderFace(s, 4u, sW, F), fE = renderFace(s, 5u, sE, F);
                    if (!(top || bottom || fN || fS || fW || fE)) return;
                    float f11 = fh(F, p), f7, f8, f9, f10;
                    if (f11 >= 1.0) { f7 = 1.0; f8 = 1.0; f9 = 1.0; f10 = 1.0; }
                    else {
                        float f12 = fh(F, p + OFF[2]), f13 = fh(F, p + OFF[3]), f14 = fh(F, p + OFF[5]), f15 = fh(F, p + OFF[4]);
                        f7 = avgH(F, f11, f12, f14, p + OFF[2] + OFF[5]);
                        f8 = avgH(F, f11, f12, f15, p + OFF[2] + OFF[4]);
                        f9 = avgH(F, f11, f13, f14, p + OFF[3] + OFF[5]);
                        f10 = avgH(F, f11, f13, f15, p + OFF[3] + OFF[4]);
                    }
                    float f16 = bottom ? 0.001 : 0.0;
                    uint lm = fluidLight(s, p);
                    uint fw = 0xFFFFu | F << 24u;
                    float hmin = min(min(f8, f10), min(f9, f7));
                    bool upCovered = (fprops[sU * 2u].x & 256u) != 0u && abs(hmin - 1.0) <= 1e-7;
                    if (top && !upCovered) {
                        f8 -= 0.001; f10 -= 0.001; f9 -= 0.001; f7 -= 0.001;
                        vec2 fl = fluidFlow(F, s, p);
                        bool still = fl.x == 0.0 && fl.y == 0.0;
                        float ang = still ? 0.0 : atan(fl.y, fl.x) - 1.5707964;
                        uint sf = still ? 1u << 28u : 0u;
                        femit(cnt, write, sec, L + 1u, p, fw | 0u << 16u | 1u << 20u | sf, f8, f10, f9, f7, lm, ang);
                        bool back = false;   // FluidState.shouldRenderBackwardUpFace(level, pos.above())
                        for (int i = -1; i <= 1; i++)
                            for (int j = -1; j <= 1; j++) {
                                uint so = stateAt(p + ivec3(i, 1, j));
                                if (fam(so) != F && !solid(so)) back = true;
                            }
                        if (back) femit(cnt, write, sec, L + 0u, p, fw | 1u << 16u | 0u << 20u | sf, f8, f10, f9, f7, lm, ang);
                    }
                    if (bottom) femit(cnt, write, sec, L + 0u, p, fw | 2u << 16u, f16, 0.0, 0.0, 0.0, fluidLight(sD, p + OFF[0]), 0.0);
                    for (uint d = 2u; d < 6u; d++) {
                        float f44 = d == 2u ? f8 : d == 3u ? f9 : d == 4u ? f10 : f7;
                        float f45 = d == 2u ? f7 : d == 3u ? f10 : d == 4u ? f8 : f9;
                        bool on = d == 2u ? fN : d == 3u ? fS : d == 4u ? fW : fE;
                        uint nb = d == 2u ? sN : d == 3u ? sS : d == 4u ? sW : sE;
                        if (!on || max(f44, f45) <= sideCover(nb, d)) continue;
                        bool overlay = (x & (1u << 16u)) != 0u && ((fprops[nb * 2u].w >> (F - 1u)) & 1u) != 0u;
                        femit(cnt, write, sec, L + d, p, fw | 3u << 16u | d << 20u | (overlay ? 2u << 28u : 0u), f44, f45, f16, 0.0, lm, 0.0);
                        if (!overlay) femit(cnt, write, sec, L + (d ^ 1u), p, fw | 4u << 16u | d << 20u, f44, f45, f16, 0.0, lm, 0.0);
                    }
                }
                #endif
                bool drawn(uint self, uint nb, uint side) {
                    uint bit = (self * uint(States) + nb) * 6u + side;
                    return ((pairs[bit >> 5u] >> (bit & 31u)) & 1u) != 0u;
                }
                bool visible(ivec3 p, uint self, uint q) {
                    uint cull = uint(quads[q * 40u + 24u]);
                    if (cull > 5u) return true;
                    return drawn(self, stateAt(p + OFF[cull]), cull);
                }
                float shadeFor(int dir, bool shade) {
                    if (!shade) return 1.0;
                    return dir == 0 ? 0.5 : dir == 1 ? 1.0 : dir < 4 ? 0.8 : 0.6;
                }
                int blend4(int a, int b, int c, int d) {
                    if (a == 0) a = d;
                    if (b == 0) b = d;
                    if (c == 0) c = d;
                    return a + b + c + d >> 2 & 16711935;
                }
                int blend8(int b0, int b1, int b2, int b3, float w0, float w1, float w2, float w3) {
                    precise float hi = float(b0 >> 16 & 255) * w0 + float(b1 >> 16 & 255) * w1 + float(b2 >> 16 & 255) * w2 + float(b3 >> 16 & 255) * w3;
                    precise float lo = float(b0 & 255) * w0 + float(b1 & 255) * w1 + float(b2 & 255) * w2 + float(b3 & 255) * w3;
                    return (int(hi) & 255) << 16 | (int(lo) & 255);
                }

                // one face: 4 brightnesses and 4 lightmap values (vanilla's vertex order)
                void light(ivec3 p, uint self, uint q, out float br[4], out int lm[4]) {
                    uint qb = q * 40u;
                    int dir = int(quads[qb + 21u]);
                    uint flags = uint(quads[qb + 22u]);
                    bool shade = (flags & 1u) != 0u, aoPath = (flags & 2u) != 0u, f0 = (flags & 4u) != 0u, f1s = (flags & 8u) != 0u, quadAo = (flags & 16u) != 0u;
                    if (!aoPath || !quadAo) {
                        // the flat path (tesselateWithoutAO, or NeoForge's calculateFaceWithoutAO for a quad without AO)
                        uint cull = uint(quads[qb + 24u]);
                        ivec3 lp = (!aoPath && cull < 6u) ? p + OFF[cull] : (f0 ? p + OFF[dir] : p);
                        int l = lightColor(self, lp);
                        float s = shadeFor(dir, shade);
                        for (int k = 0; k < 4; k++) { br[k] = s; lm[k] = l; }
                        return;
                    }
                    // AmbientOcclusionFace.calculate
                    int tb = dir * 40;
                    ivec3 base = f0 ? p + OFF[dir] : p;
                    ivec3 c0 = OFF[ao[tb]], c1 = OFF[ao[tb + 1]], c2 = OFF[ao[tb + 2]], c3 = OFF[ao[tb + 3]];
                    uint s0 = stateAt(base + c0), s1 = stateAt(base + c1), s2 = stateAt(base + c2), s3 = stateAt(base + c3);
                    int i = lightColor(s0, base + c0), j = lightColor(s1, base + c1), k = lightColor(s2, base + c2), l = lightColor(s3, base + c3);
                    float f = shadeB(s0), f1 = shadeB(s1), f2 = shadeB(s2), f3 = shadeB(s3);
                    bool flag = aoOpen(s0), flag1 = aoOpen(s1), flag2 = aoOpen(s2), flag3 = aoOpen(s3);
                    float f4, f5, f6, f7;
                    int i1, j1, k1, l1;
                    if (!flag2 && !flag) { f4 = f; i1 = i; } else { ivec3 pp = base + c0 + c2; uint ss = stateAt(pp); f4 = shadeB(ss); i1 = lightColor(ss, pp); }
                    if (!flag3 && !flag) { f5 = f; j1 = i; } else { ivec3 pp = base + c0 + c3; uint ss = stateAt(pp); f5 = shadeB(ss); j1 = lightColor(ss, pp); }
                    if (!flag2 && !flag1) { f6 = f1; k1 = j; } else { ivec3 pp = base + c1 + c2; uint ss = stateAt(pp); f6 = shadeB(ss); k1 = lightColor(ss, pp); }
                    if (!flag3 && !flag1) { f7 = f1; l1 = j; } else { ivec3 pp = base + c1 + c3; uint ss = stateAt(pp); f7 = shadeB(ss); l1 = lightColor(ss, pp); }
                    int i3 = lightColor(self, p);
                    ivec3 np = p + OFF[dir];
                    uint ns = stateAt(np);
                    if (f0 || !solid(ns)) i3 = lightColor(ns, np);
                    float f8 = f0 ? shadeB(stateAt(base)) : shadeB(self);
                    int r0 = ao[tb + 4], r1 = ao[tb + 5], r2 = ao[tb + 6], r3 = ao[tb + 7];
                    precise float f29 = (f3 + f + f5 + f8) * 0.25;
                    precise float f31 = (f2 + f + f4 + f8) * 0.25;
                    precise float f32 = (f2 + f1 + f6 + f8) * 0.25;
                    precise float f33 = (f3 + f1 + f7 + f8) * 0.25;
                    if (f1s) {
                        float w[16];
                        for (int v = 0; v < 4; v++)
                            for (int m = 0; m < 4; m++) {
                                int wi = tb + 8 + v * 8 + m * 2;
                                precise float ww = quads[qb + 25u + uint(ao[wi])] * quads[qb + 25u + uint(ao[wi + 1])];
                                w[v * 4 + m] = ww;
                            }
                        precise float b0 = f29 * w[0] + f31 * w[1] + f32 * w[2] + f33 * w[3];
                        precise float b1 = f29 * w[4] + f31 * w[5] + f32 * w[6] + f33 * w[7];
                        precise float b2 = f29 * w[8] + f31 * w[9] + f32 * w[10] + f33 * w[11];
                        precise float b3 = f29 * w[12] + f31 * w[13] + f32 * w[14] + f33 * w[15];
                        int i2 = blend4(l, i, j1, i3), j2 = blend4(k, i, i1, i3), k2 = blend4(k, j, k1, i3), l2 = blend4(l, j, l1, i3);
                        int m0 = blend8(i2, j2, k2, l2, w[0], w[1], w[2], w[3]), m1 = blend8(i2, j2, k2, l2, w[4], w[5], w[6], w[7]);
                        int m2 = blend8(i2, j2, k2, l2, w[8], w[9], w[10], w[11]), m3 = blend8(i2, j2, k2, l2, w[12], w[13], w[14], w[15]);
                        // r0..r3 is a permutation: each corner picks its value by comparison (no write through a runtime index)
                        for (int c = 0; c < 4; c++) {
                            br[c] = c == r0 ? b0 : c == r1 ? b1 : c == r2 ? b2 : b3;
                            lm[c] = c == r0 ? m0 : c == r1 ? m1 : c == r2 ? m2 : m3;
                        }
                    } else {
                        int m0 = blend4(l, i, j1, i3), m1 = blend4(k, i, i1, i3), m2 = blend4(k, j, k1, i3), m3 = blend4(l, j, l1, i3);
                        for (int c = 0; c < 4; c++) {
                            br[c] = c == r0 ? f29 : c == r1 ? f31 : c == r2 ? f32 : f33;
                            lm[c] = c == r0 ? m0 : c == r1 ? m1 : c == r2 ? m2 : m3;
                        }
                    }
                    precise float f30 = shadeFor(dir, shade);
                    for (int v = 0; v < 4; v++) { precise float bv = br[v] * f30; br[v] = bv; }
                }

                void main() {
                    ivec3 o = sections[gl_WorkGroupID.x].xyz;
                    uint inv = gl_LocalInvocationIndex, sec = gl_WorkGroupID.x;
                    uint mine[24];
                    for (uint l = 0u; l < 24u; l++) mine[l] = 0u;
                    for (uint i = inv; i < 4096u; i += 256u) {
                        ivec3 p = o + ivec3(i & 15u, i >> 8u, (i >> 4u) & 15u);
                        uint s = stateAt(p);
                        if (s == 0u) continue;
                        uint nib;
                        Seeds sd = seeds(p, s, nib);
                        uvec4 le = lib[s];
                        if ((le.w & 128u) != 0u) continue;   // VANILLA_MODEL: vanilla's mesh of this section draws it
                        for (uint ei = le.x; ei < le.x + le.y; ei++) {
                            uvec2 e = pickEntry(ei, sd);
                            for (uint k = 0u; k < e.y; k++) if (visible(p, s, e.x + k)) mine[bucketOf(e.x + k)]++;
                        }
                        #ifdef FLUIDS
                        if (fam(s) != 0u) fluidFaces(p, s, sec, mine, false);
                        #endif
                    }
                    for (uint l = 0u; l < 24u; l++) counts[l * 256u + inv] = mine[l];
                    barrier();
                    if (inv < 24u) {   // per bucket: the invocations' offsets, in invocation order (deterministic)
                        uint run = 0u;
                        for (uint t = 0u; t < 256u; t++) { uint c = counts[inv * 256u + t]; counts[inv * 256u + t] = run; run += c; }
                        if (Pass == 0) counts4[sec * 24u + inv] = run;
                    }
                    if (Pass == 0) return;
                    barrier();
                    uint w[24];
                    for (uint l = 0u; l < 24u; l++) w[l] = bases[sec * 24u + l] + counts[l * 256u + inv];
                    for (uint i = inv; i < 4096u; i += 256u) {
                        ivec3 p = o + ivec3(i & 15u, i >> 8u, (i >> 4u) & 15u);
                        uint s = stateAt(p);
                        if (s == 0u) continue;
                        uint nib;
                        Seeds sd = seeds(p, s, nib);
                        uvec4 le = lib[s];
                        if ((le.w & 128u) != 0u) continue;   // VANILLA_MODEL: vanilla's mesh of this section draws it
                        for (uint ei = le.x; ei < le.x + le.y; ei++) {
                            uvec2 e = pickEntry(ei, sd);
                            for (uint k = 0u; k < e.y; k++) {
                                uint q = e.x + k;
                                if (!visible(p, s, q)) continue;
                                uint bk = bucketOf(q), at = w[bk]++;
                                if (at < capacity && at < bases[sec * 24u + bk] + counts4[sec * 24u + bk]) {
                                    float br[4];
                                    int lm[4];
                                    light(p, s, q, br, lm);
                                    #ifdef VERTS
                                    if (bk < 18u) quadVerts(at, p, q, nib, br, lm); else {
                                    #endif
                                    uint b = at * FACE_STRIDE;
                                    faces[b] = encodeCell(p);
                                    faces[b + 1u] = q | nib << 16u;
                                    for (int v = 0; v < 4; v++) faces[b + 2u + uint(v)] = floatBitsToUint(br[v]);
                                    faces[b + 6u] = uint(lm[0] & 255) | uint((lm[0] >> 16) & 255) << 8u | uint(lm[1] & 255) << 16u | uint((lm[1] >> 16) & 255) << 24u;
                                    faces[b + 7u] = uint(lm[2] & 255) | uint((lm[2] >> 16) & 255) << 8u | uint(lm[3] & 255) << 16u | uint((lm[3] >> 16) & 255) << 24u;
                                    #ifdef VERTS
                                    }
                                    #endif
                                }
                            }
                        }
                        #ifdef FLUIDS
                        if (fam(s) != 0u) fluidFaces(p, s, sec, w, true);
                        #endif
                    }
                }
                """;
        return mesh.replace("%PRELUDE%", prelude);
    }

    private static boolean compileWith(String mesh) {
        String scan = """
                #version 430
                layout(local_size_x = 256) in;
                uniform int Sections;
                layout(std430, binding = 15) buffer Counts { uint counts4[]; };
                layout(std430, binding = 7) buffer Bases { uint bases[]; };
                layout(std430, binding = 3) buffer Totals { uint totals[8]; };
                shared uint part[256];
                shared uint layerSum;
                shared uint layerBase;
                void main() {   // layer by layer: each layer's faces contiguous, sections in list order (deterministic)
                    uint t = gl_LocalInvocationIndex, n = uint(Sections);
                    uint per = (n + 255u) / 256u, from = min(t * per, n), to = min(from + per, n);
                    if (t == 0u) { layerBase = 0u; for (uint k = 0u; k < 8u; k++) totals[k] = 0u; }
                    barrier();
                    for (uint l = 0u; l < 24u; l++) {
                        uint sum = 0u;
                        for (uint s = from; s < to; s++) sum += counts4[s * 24u + l];
                        part[t] = sum;
                        barrier();
                        if (t == 0u) {   // 256 partial sums: a short serial prefix
                            uint run = layerBase;
                            for (uint k = 0u; k < 256u; k++) { uint c = part[k]; part[k] = run; run += c; }
                            totals[l / 6u] += run - layerBase;   // per layer
                            layerBase = run;
                        }
                        barrier();
                        uint at = part[t];
                        for (uint s = from; s < to; s++) { bases[s * 24u + l] = at; at += counts4[s * 24u + l]; }
                        barrier();
                    }
                }
                """;
        String vs = """
                #version 430
                layout(std430, binding = 13) buffer Out { uint counter; uint capacity; uint pad0; uint pad1; uint faces[]; };
                layout(std430, binding = 14) buffer Quads { float quads[]; };
                layout(std430, binding = 5) buffer Offsets { float offs[]; };
                uniform mat4 ViewProj;
                uniform vec3 Cam;
                out vec2 uv;
                out vec2 lmuv;
                out float bright;
                out float dist;
                flat out float tint;
                void main() {
                    uint f = uint(gl_VertexID) >> 2u;   // the base vertex is the face's first corner (4 per face)
                    int c = gl_VertexID & 3;
                    uint b = f * 8u;
                    uint pk = faces[b];
                    vec3 cell = vec3(float(pk & 1023u), float((pk >> 10u) & 1023u), float((pk >> 20u) & 1023u));
                    uint w1 = faces[b + 1u];
                    uint q = (w1 & 0xFFFFu) * 40u;
                    vec3 off = vec3(0.0);
                    int ot = int(quads[q + 37u]);
                    if (ot > 0) {
                        uint nib = w1 >> 16u, tb = uint(ot - 1) * 48u;
                        off = vec3(offs[tb + (nib & 15u)], offs[tb + 16u + ((nib >> 4u) & 15u)], offs[tb + 32u + ((nib >> 8u) & 15u)]);
                    }
                    vec3 p = vec3(quads[q + uint(c * 3)], quads[q + uint(c * 3 + 1)], quads[q + uint(c * 3 + 2)]);
                    uv = vec2(quads[q + 12u + uint(c * 2)], quads[q + 13u + uint(c * 2)]);
                    bright = uintBitsToFloat(faces[b + 2u + uint(c)]);
                    uint lmw = faces[b + 6u + uint(c >> 1)] >> uint((c & 1) * 16);
                    lmuv = vec2(float(lmw & 255u), float((lmw >> 8u) & 255u));
                    tint = quads[q + 20u];
                    vec3 rel = cell + off + p - Cam;
                    dist = max(length(rel.xz), abs(rel.y));   // vanilla's cylindrical terrain fog distance
                    gl_Position = ViewProj * vec4(rel, 1.0);
                }
                """;
        String fsHead = """
                #version 430
                uniform sampler2D Atlas;
                uniform sampler2D Lightmap;
                uniform vec4 FogColorV;
                uniform vec2 FogRange;
                uniform float AlphaCut;
                in vec2 uv;
                in vec2 lmuv;
                in float bright;
                in float dist;
                flat in float tint;
                out vec4 fragColor;
                void main() {
                    vec4 c = texture(Atlas, uv);
                """;
        String fsTail = """
                    vec3 col = c.rgb * bright;
                    if (tint >= 0.0) col *= vec3(0.47, 0.74, 0.36);
                    col *= texture(Lightmap, clamp(lmuv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
                    float fv = dist <= FogRange.x ? 0.0 : dist < FogRange.y ? smoothstep(FogRange.x, FogRange.y, dist) : 1.0;
                    fragColor = vec4(mix(col, FogColorV.rgb, fv * FogColorV.a), 1.0);
                }
                """;
        String fsSolid = fsHead + fsTail;
        String fsCutout = fsHead + "        if (c.a < AlphaCut) discard;\n" + fsTail;
        meshProg = program(mesh, null, null);
        scanProg = program(scan, null, null);
        drawSolid = program(null, vs, fsSolid);
        drawCutout = program(null, vs, fsCutout);
        if (meshProg == 0 || scanProg == 0 || drawSolid == 0 || drawCutout == 0) return false;
        GlStateManager._glUseProgram(meshProg);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "Grid"), 9);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "Light"), 10);
        uDims = GL20C.glGetUniformLocation(meshProg, "Dims");
        uStates = GL20C.glGetUniformLocation(meshProg, "States");
        uOrigin = GL20C.glGetUniformLocation(meshProg, "Origin");
        uPass = GL20C.glGetUniformLocation(meshProg, "Pass");
        GlStateManager._glUseProgram(scanProg);
        uScanSections = GL20C.glGetUniformLocation(scanProg, "Sections");
        int[][] us = {uSolid, uCutout};
        int[] ps = {drawSolid, drawCutout};
        for (int k = 0; k < 2; k++) {
            GlStateManager._glUseProgram(ps[k]);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ps[k], "Atlas"), 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ps[k], "Lightmap"), 2);
            us[k][0] = GL20C.glGetUniformLocation(ps[k], "ViewProj");
            us[k][1] = GL20C.glGetUniformLocation(ps[k], "Cam");
            us[k][2] = GL20C.glGetUniformLocation(ps[k], "FogColorV");
            us[k][3] = GL20C.glGetUniformLocation(ps[k], "FogRange");
            us[k][4] = GL20C.glGetUniformLocation(ps[k], "AlphaCut");
        }
        GlStateManager._glUseProgram(0);
        // a quad index buffer (0 1 2 0 2 3 per quad, as vanilla's quads): the VAO keeps it
        vao = GlTrack.vao(GL30C.glGenVertexArrays());
        GlStateManager._glBindVertexArray(vao);
        quadIndexBuf = GlTrack.buf(GL15C.glGenBuffers());
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuf);
        IntBuffer ib = MemoryUtil.memAllocInt(MAX_QUADS_PER_DRAW * 6);
        for (int qd = 0; qd < MAX_QUADS_PER_DRAW; qd++) {
            int v = qd * 4;
            ib.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
        }
        ib.flip();
        GL44C.glBufferStorage(GL15C.GL_ELEMENT_ARRAY_BUFFER, ib, 0);
        MemoryUtil.memFree(ib);
        GlStateManager._glBindVertexArray(0);
        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        return true;
    }

    static int program(String compute, String vertex, String fragment) {
        int p = GlTrack.prog(GL20C.glCreateProgram());
        if (compute != null && !attach(p, GL43C.GL_COMPUTE_SHADER, compute)) return 0;
        if (vertex != null && !attach(p, GL20C.GL_VERTEX_SHADER, vertex)) return 0;
        if (fragment != null && !attach(p, GL20C.GL_FRAGMENT_SHADER, fragment)) return 0;
        GL20C.glLinkProgram(p);
        if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
            LOG.error("[sleep/voxel] compute link failed: {}", GL20C.glGetProgramInfoLog(p));
            return 0;
        }
        return p;
    }

    private static boolean attach(int p, int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            LOG.error("[sleep/voxel] compute shader compile failed: {}", GL20C.glGetShaderInfoLog(s));
            return false;
        }
        GL20C.glAttachShader(p, s);
        GL20C.glDeleteShader(s);
        return true;
    }
}
