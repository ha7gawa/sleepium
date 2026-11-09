package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * exp/quad-gpu (client): baked quads written into the level's batched buffers (VertexConsumer.putBulkData - block
 * entity renderers' models, Immersive Engineering's renderModelTESRFast, block models drawn by renderers) drawn from VRAM:
 * a run of quads written with one pose, light, overlay and colour becomes one record {pose, normal matrix, light,
 * overlay} over geometry uploaded once - the per-vertex work of every frame (unpack, colour, baked light, transform,
 * write) is gone, also for parts that move every frame (a crusher's barrels, a turret's head), which the render cache
 * cannot keep.
 *
 * <p>The geometry of a run: its quads (the same objects, in order) and their colour inputs (brightness per vertex,
 * red / green / blue / alpha, whether the quad's own colour is read), each vertex made with putBulkData's arithmetic -
 * the colour bytes, the quad's baked light (max with the record's on the GPU: NeoForge's applyBakedLighting), the
 * baked or face normal as NeoForge carries it from vertex to vertex. ItemGpu's mode 7 then computes position and normal
 * as JOML does on the CPU. A run is taken only when its light is one value for all four vertices, its type is batched
 * by the level's buffer source with a patched shader and its draws commute (opaque, LEQUAL, writing depth: the records
 * go ahead of the type's batch); quads made anew each frame (other objects every time) never repeat and stay vanilla:
 * geometry is built for a run seen in two frames.
 *
 * <p>Runs are kept open while the same builder gets quads with the same pose and inputs, and closed at the next other
 * call, before the builder is built (drawn) and at the frame's end; a closed run without geometry is written the vanilla
 * way then (its type's draws commute, so the later write changes nothing).
 */
public final class QuadGpu {
    public static long runs, recorded, quadsRecorded, quadsVanilla, built, refused, puts, notRender, notOneLight, noType, firstSeen, sameFrame, small, full;
    /** quads per vanilla-written run (log2 buckets) */
    public static final long[] VAN_SIZES = new long[12];
    /** why a builder is not taken: 0 another source, 1 not started there, 2 format / mode, 3 does not commute, 4 not a record target */
    public static final long[] WHY_TYPE = new long[5];
    public static volatile boolean enabled = true;
    private static final int MAX_GEOS = Integer.getInteger("lab.quadgpu.maxGeometries", 8192);
    private static final int MIN_QUADS = Integer.getInteger("lab.quadgpu.minQuads", 1);

    private QuadGpu() {}

    // ------------------------------------------------------------------ the open run (render thread)

    private static Thread renderThread;
    private static BufferBuilder runBb;
    private static RenderType runType;
    private static final PoseStack.Pose RUN_POSE = new PoseStack().last();
    private static int runLight, runOverlay, runN;
    private static float runR, runG, runB, runA, br0, br1, br2, br3;
    private static boolean runReadAlpha;
    private static BakedQuad[] runQuads = new BakedQuad[256];
    private static long frame;

    /** the last builder asked about and its type in the level's source (one map lookup to confirm it) */
    private static BufferBuilder lastBb;
    private static RenderType lastType;

    /**
     * From BufferBuilder.putBulkData (QuadGpuMixin): true if the quad was taken into a run (written later from VRAM or
     * the vanilla way at the run's close); false: write it now the vanilla way.
     */
    public static boolean put(BufferBuilder bb, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float r, float g, float b, float a,
                              int[] lightmap, int overlay, boolean readAlpha) {
        if (!enabled) return false;
        puts++;
        Thread t = Thread.currentThread();
        if (t != renderThread) {
            if (renderThread != null || !RenderSystem.isOnRenderThread()) {
                notRender++;
                return false;
            }
            renderThread = t;
        }
        int light = lightmap[0];
        if (lightmap[1] != light || lightmap[2] != light || lightmap[3] != light || quad.getVertices().length != 32) {
            notOneLight++;
            close();
            return false;
        }
        if (runN > 0) {
            if (bb == runBb && light == runLight && overlay == runOverlay && r == runR && g == runG && b == runB && a == runA
                    && readAlpha == runReadAlpha && brightness[0] == br0 && brightness[1] == br1 && brightness[2] == br2 && brightness[3] == br3
                    && samePose(pose)) {
                if (runN == runQuads.length) runQuads = java.util.Arrays.copyOf(runQuads, runN * 2);
                runQuads[runN++] = quad;
                return true;
            }
            close();
        }
        RenderType type = typeOf(bb);
        if (type == null) {
            noType++;
            return false;
        }
        runBb = bb;
        runType = type;
        RUN_POSE.pose().set(pose.pose());
        RUN_POSE.normal().set(pose.normal());
        ((dev.lull.opt.PoseAccessor) (Object) RUN_POSE).sleep$setTrusted(((dev.lull.opt.PoseAccessor) (Object) pose).sleep$trusted());
        runLight = light;
        runOverlay = overlay;
        runR = r;
        runG = g;
        runB = b;
        runA = a;
        runReadAlpha = readAlpha;
        br0 = brightness[0];
        br1 = brightness[1];
        br2 = brightness[2];
        br3 = brightness[3];
        runQuads[0] = quad;
        runN = 1;
        return true;
    }

    private static boolean samePose(PoseStack.Pose pose) {
        return pose.pose().equals(RUN_POSE.pose()) && pose.normal().equals(RUN_POSE.normal())
                && ((dev.lull.opt.PoseAccessor) (Object) pose).sleep$trusted() == ((dev.lull.opt.PoseAccessor) (Object) RUN_POSE).sleep$trusted();
    }

    /** The builder's type when it is the level source's started builder of a type that takes records; else null. */
    private static RenderType typeOf(BufferBuilder bb) {
        MultiBufferSource.BufferSource src = Minecraft.getInstance().renderBuffers().bufferSource();
        if (src.getClass() != MultiBufferSource.BufferSource.class) {   // another mod's source: its own rules
            WHY_TYPE[0]++;
            return null;
        }
        Map<RenderType, BufferBuilder> started = ((dev.lull.opt.BufferSourceAccessor) src).sleep$started();
        RenderType t = null;
        if (bb == lastBb && lastType != null && started.get(lastType) == bb) t = lastType;
        else {
            for (Map.Entry<RenderType, BufferBuilder> e : started.entrySet())
                if (e.getValue() == bb) {
                    t = e.getKey();
                    break;
                }
            lastBb = bb;
            lastType = t;
        }
        if (t == null) {
            WHY_TYPE[1]++;
            return null;
        }
        if (t.format() != DefaultVertexFormat.BLOCK && t.format() != DefaultVertexFormat.NEW_ENTITY || t.mode() != VertexFormat.Mode.QUADS) {
            WHY_TYPE[2]++;
            return null;
        }
        if (!EntityBatch.commutes(t)) {
            WHY_TYPE[3]++;
            return null;
        }
        if (!PartBatch.quadTarget(t, bb, src)) {
            if (WHY_TYPE[4]++ == 0) System.out.println("[sleep/opt] quad gpu: not a record target: " + PartBatch.itemWhy(t, bb, src));
            return null;
        }
        return t;
    }

    /** Before a builder is built (drawn): its open run closed into it. */
    public static void beforeBuild(BufferBuilder bb) {
        // (the source has already taken the builder off its started ones: the run's type stands, it is this builder's)
        if (runN > 0 && bb == runBb) close(true);
    }

    /** End of the frame's level render: the open run closed. */
    public static void endFrame() {
        close(false);
        frame++;
        if ((frame & 1023) == 0) SEEN.clear();
    }

    // ------------------------------------------------------------------ close: a record over known geometry, or vanilla

    /** a run's key: its quads (identity, in order) and colour inputs */
    private static final class Key {
        BakedQuad[] quads;
        int n;
        float r, g, b, a, b0, b1, b2, b3;
        boolean readAlpha;
        int hash;

        void hash() {
            int h = 1;
            for (int i = 0; i < n; i++) h = 31 * h + System.identityHashCode(quads[i]);
            h = 31 * h + Float.floatToIntBits(r);
            h = 31 * h + Float.floatToIntBits(g);
            h = 31 * h + Float.floatToIntBits(b);
            h = 31 * h + Float.floatToIntBits(a);
            h = 31 * h + Float.floatToIntBits(b0) + 3 * Float.floatToIntBits(b1) + 7 * Float.floatToIntBits(b2) + 11 * Float.floatToIntBits(b3);
            hash = readAlpha ? h * 17 : h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k) || k.hash != hash || k.n != n || k.readAlpha != readAlpha || k.r != r || k.g != g || k.b != b || k.a != a
                    || k.b0 != b0 || k.b1 != b1 || k.b2 != b2 || k.b3 != b3) return false;
            for (int i = 0; i < n; i++) if (k.quads[i] != quads[i]) return false;
            return true;
        }

        Key copy() {
            Key k = new Key();
            k.quads = java.util.Arrays.copyOf(quads, n);
            k.n = n;
            k.r = r;
            k.g = g;
            k.b = b;
            k.a = a;
            k.b0 = b0;
            k.b1 = b1;
            k.b2 = b2;
            k.b3 = b3;
            k.readAlpha = readAlpha;
            k.hash = hash;
            return k;
        }
    }

    /** geometry: {first vertex, quads} in PartBatch's arena */
    private static final Map<Key, int[]> GEOS = new HashMap<>();
    /** runs seen once (the frame they were seen in): built when seen in a later frame */
    private static final Map<Key, Long> SEEN = new HashMap<>();
    private static final Key PROBE = new Key();

    private static void close() {
        close(false);
    }

    /** building: called right before the run's builder is built (its type known, no longer among the source's started ones) */
    private static void close(boolean building) {
        int n = runN;
        if (n == 0) return;
        runN = 0;
        runs++;
        BufferBuilder bb = runBb;
        Key k = PROBE;
        k.quads = runQuads;
        k.n = n;
        k.r = runR;
        k.g = runG;
        k.b = runB;
        k.a = runA;
        k.b0 = br0;
        k.b1 = br1;
        k.b2 = br2;
        k.b3 = br3;
        k.readAlpha = runReadAlpha;
        k.hash();
        int[] geo = n >= MIN_QUADS ? GEOS.get(k) : null;
        if (n < MIN_QUADS) small++;
        else if (geo == null && GEOS.size() >= MAX_GEOS) full++;
        if (geo == null && n >= MIN_QUADS && GEOS.size() < MAX_GEOS) {
            Long seen = SEEN.get(k);
            if (seen == null) {
                SEEN.put(k.copy(), frame);
                firstSeen++;
            } else if (seen == frame) sameFrame++;
            else {
                SEEN.remove(k);
                geo = build(k);
                if (geo != null) GEOS.put(k.copy(), geo);
            }
        }
        if (geo != null && (building || typeOf(bb) == runType)) {
            PartBatch.queueItem(runType, bb, geo[0], geo[1], RUN_POSE, runLight, runOverlay);
            recorded++;
            quadsRecorded += n;
        } else {
            float[] brightness = {br0, br1, br2, br3};
            int[] lightmap = {runLight, runLight, runLight, runLight};
            for (int i = 0; i < n; i++)
                vanilla(bb, RUN_POSE, runQuads[i], brightness, runR, runG, runB, runA, lightmap, runOverlay, runReadAlpha);
            quadsVanilla += n;
            VAN_SIZES[Math.min(11, 31 - Integer.numberOfLeadingZeros(n))]++;
        }
        java.util.Arrays.fill(runQuads, 0, n, null);
    }

    // ------------------------------------------------------------------ geometry (render thread)

    private static ByteBuffer scratch = MemoryUtil.memAlloc(1 << 16);

    /** The run's vertices as putBulkData makes them (identity pose), in PartBatch's layout, uploaded: {first, quads}. */
    private static int[] build(Key k) {
        int n = k.n * 4;
        if (scratch.capacity() < n * PartBatch.STRIDE) {
            MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Math.max(n * PartBatch.STRIDE, scratch.capacity() * 2));
        }
        long base = MemoryUtil.memAddress(scratch);
        float[] br = {k.b0, k.b1, k.b2, k.b3};
        int alphaK = (int) (k.a * 255.0F);
        int v = 0;
        for (int qi = 0; qi < k.n; qi++) {
            BakedQuad q = k.quads[qi];
            int[] d = q.getVertices();
            var dn = q.getDirection().getNormal();
            byte cx = (byte) (dn.getX() * 127), cy = (byte) (dn.getY() * 127), cz = (byte) (dn.getZ() * 127);
            boolean carried = false;   // NeoForge's applyBakedNormals keeps the last baked normal for the quad's next vertices
            for (int l = 0; l < 4; l++) {
                long at = base + (long) v * PartBatch.STRIDE;
                int o8 = l * 8;
                MemoryUtil.memPutFloat(at, Float.intBitsToFloat(d[o8]));
                MemoryUtil.memPutFloat(at + 4, Float.intBitsToFloat(d[o8 + 1]));
                MemoryUtil.memPutFloat(at + 8, Float.intBitsToFloat(d[o8 + 2]));
                int c = d[o8 + 3];   // bytes R, G, B, A
                float f3, f4, f5;
                if (k.readAlpha) {
                    f3 = (float) (c & 255) * br[l] * k.r;
                    f4 = (float) (c >> 8 & 255) * br[l] * k.g;
                    f5 = (float) (c >> 16 & 255) * br[l] * k.b;
                } else {
                    f3 = br[l] * k.r * 255.0F;
                    f4 = br[l] * k.g * 255.0F;
                    f5 = br[l] * k.b * 255.0F;
                }
                int va = k.readAlpha ? (int) ((k.a * (float) (c >>> 24) / 255.0F) * 255) : alphaK;
                int argb = FastColor.ARGB32.color(va, (int) f3, (int) f4, (int) f5);   // as putBulkData packs it (then BufferBuilder writes ABGR)
                MemoryUtil.memPutFloat(at + 12, Float.intBitsToFloat(d[o8 + 4]));
                MemoryUtil.memPutFloat(at + 16, Float.intBitsToFloat(d[o8 + 5]));
                int nrm = d[o8 + 7];
                byte nx = (byte) nrm, ny = (byte) (nrm >> 8), nz = (byte) (nrm >> 16);
                if (nx != 0 || ny != 0 || nz != 0) {
                    cx = nx;
                    cy = ny;
                    cz = nz;
                    carried = true;
                }
                MemoryUtil.memPutByte(at + 20, cx);
                MemoryUtil.memPutByte(at + 21, cy);
                MemoryUtil.memPutByte(at + 22, cz);
                MemoryUtil.memPutByte(at + 23, (byte) (carried ? 16 : 0));   // 16: a baked normal, never normalised
                MemoryUtil.memPutByte(at + 24, (byte) (argb >> 16 & 255));
                MemoryUtil.memPutByte(at + 25, (byte) (argb >> 8 & 255));
                MemoryUtil.memPutByte(at + 26, (byte) (argb & 255));
                MemoryUtil.memPutByte(at + 27, (byte) (argb >>> 24));
                int uv2 = d[o8 + 6];   // the quad's baked light (max with the record's on the GPU)
                MemoryUtil.memPutShort(at + 28, (short) (uv2 & 0xFFFF));
                MemoryUtil.memPutShort(at + 30, (short) (uv2 >>> 16));
                v++;
            }
        }
        built++;
        return PartBatch.uploadItem(scratch, n);
    }

    // ------------------------------------------------------------------ vanilla

    /** VertexConsumer.putBulkData's default body (NeoForge 21.1), for the builder. */
    public static void vanilla(VertexConsumer self, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha,
                               int[] lightmap, int packedOverlay, boolean readAlpha) {
        int[] aint = quad.getVertices();
        var vec3i = quad.getDirection().getNormal();
        Matrix4f matrix4f = pose.pose();
        Vector3f vector3f = pose.transformNormal((float) vec3i.getX(), (float) vec3i.getY(), (float) vec3i.getZ(), new Vector3f());
        int j = aint.length / 8;
        int k = (int) (alpha * 255.0F);
        try (MemoryStack memorystack = MemoryStack.stackPush()) {
            ByteBuffer bytebuffer = memorystack.malloc(DefaultVertexFormat.BLOCK.getVertexSize());
            IntBuffer intbuffer = bytebuffer.asIntBuffer();
            for (int l = 0; l < j; l++) {
                intbuffer.clear();
                intbuffer.put(aint, l * 8, 8);
                float f = bytebuffer.getFloat(0);
                float f1 = bytebuffer.getFloat(4);
                float f2 = bytebuffer.getFloat(8);
                float f3;
                float f4;
                float f5;
                if (readAlpha) {
                    float f6 = (float) (bytebuffer.get(12) & 255);
                    float f7 = (float) (bytebuffer.get(13) & 255);
                    float f8 = (float) (bytebuffer.get(14) & 255);
                    f3 = f6 * brightness[l] * red;
                    f4 = f7 * brightness[l] * green;
                    f5 = f8 * brightness[l] * blue;
                } else {
                    f3 = brightness[l] * red * 255.0F;
                    f4 = brightness[l] * green * 255.0F;
                    f5 = brightness[l] * blue * 255.0F;
                }
                int vertexAlpha = readAlpha ? (int) ((alpha * (float) (bytebuffer.get(15) & 255) / 255.0F) * 255) : k;
                int i1 = FastColor.ARGB32.color(vertexAlpha, (int) f3, (int) f4, (int) f5);
                int j1 = self.applyBakedLighting(lightmap[l], bytebuffer);
                float f10 = bytebuffer.getFloat(16);
                float f9 = bytebuffer.getFloat(20);
                Vector3f vector3f1 = matrix4f.transformPosition(f, f1, f2, new Vector3f());
                self.applyBakedNormals(vector3f, bytebuffer, pose.normal());
                self.addVertex(vector3f1.x(), vector3f1.y(), vector3f1.z(), i1, f10, f9, packedOverlay, j1, vector3f.x(), vector3f.y(), vector3f.z());
            }
        }
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "quad gpu: puts %d (off the render thread %d, light per vertex %d, builder not taken %d %s), runs %d, recorded %d (%d quads), vanilla quads %d (first seen %d, again in the frame %d, small %d, full %d, sizes %s), geometries %d, seen %d",
                puts, notRender, notOneLight, noType, java.util.Arrays.toString(WHY_TYPE), runs, recorded, quadsRecorded, quadsVanilla, firstSeen, sameFrame, small, full,
                java.util.Arrays.toString(VAN_SIZES), built, SEEN.size());
    }
}
