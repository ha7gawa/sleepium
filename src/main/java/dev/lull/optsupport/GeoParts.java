package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Method;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.renderer.GeoRenderer;

/**
 * GeckoLib bones from VRAM (GPU skinning through PartBatch): a bone's cubes are static in the bone's space, only the
 * bone's matrix changes. From GeoCubesMixin's renderCubesOfBone: the bone becomes one PartBatch record (its pose and
 * normal matrix, colour, light, overlay); its geometry is GeckoLib's own renderCube with an identity pose, recorded
 * once per bone (key: its first cube, shared by the bone copies of GeoParallel).
 * <p>
 * Not taken (GeckoLib's CPU path as before): a renderer class whose renderCube or createVerticesOfQuad is its own (the
 * geometry could depend on per-frame state); a bone with a flat cube (GeckoLib flips such normals by the posed
 * direction, fixInvertedFlatCube); a buffer PartBatch cannot draw (not a batched NEW_ENTITY type with a patched shader,
 * or off the render thread).
 */
public final class GeoParts {
    private GeoParts() {}

    /**
     * Per renderer class: null = not eligible; else the field that switches a Dynamic renderer's own vertex path on
     * (DynamicGeo*Renderer.textureOverride: a bone with its own texture has rescaled UVs; without one its
     * createVerticesOfQuad is GeoRenderer's), or NO_FIELD for renderers with GeoRenderer's renderCube / createVerticesOfQuad.
     */
    private static final java.lang.reflect.Field NO_FIELD;

    static {
        try {
            NO_FIELD = dev.lull.Names.fieldOf(GeoParts.class, "NO_FIELD");
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ClassValue<java.lang.reflect.Field> ELIGIBLE = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Field computeValue(Class<?> c) {
            java.lang.reflect.Field out = null;
            try {
                Method cube = dev.lull.Names.publicMethod(c, "renderCube", PoseStack.class, GeoCube.class, VertexConsumer.class, int.class, int.class, int.class);
                Method quad = dev.lull.Names.publicMethod(c, "createVerticesOfQuad", GeoQuad.class, Matrix4f.class, Vector3f.class, VertexConsumer.class, int.class, int.class, int.class);
                Class<?> qd = quad.getDeclaringClass();
                if (cube.getDeclaringClass() == GeoRenderer.class) {
                    if (qd == GeoRenderer.class) out = NO_FIELD;
                    else if (qd.getName().startsWith("software.bernie.geckolib.renderer.specialty.DynamicGeo")) {
                        java.lang.reflect.Field f = dev.lull.Names.fieldOf(qd, "textureOverride");
                        f.setAccessible(true);
                        out = f;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                out = null;
            }
            System.out.println("[sleep/opt] GeckoLib bones from VRAM: " + c.getName() + (out == null ? " kept on the CPU path (own renderCube / createVerticesOfQuad)"
                    : out == NO_FIELD ? " eligible" : " eligible for bones without a texture override"));
            return out;
        }
    };

    private static final PartBatch.GeometrySource SOURCE = GeoParts::emit;

    /** True if the bone was queued (nothing written to the buffer). */
    public static boolean capture(GeoRenderer<?> renderer, PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int light, int overlay, int colour) {
        List<GeoCube> cubes = bone.getCubes();
        if (cubes.isEmpty()) return false;
        java.lang.reflect.Field dyn = ELIGIBLE.get(renderer.getClass());
        if (dyn == null) return false;
        if (dyn != NO_FIELD) {
            try {
                if (dyn.get(renderer) != null) return false;   // this bone has its own texture: Dynamic's UVs
            } catch (IllegalAccessException e) {
                return false;
            }
        }
        PartBatch.JobParts jp;
        boolean queued = buffer instanceof RecordingBuilder rb && rb.type != null && (jp = ParallelRender.jobParts()) != null
                ? PartBatch.captureGeoInJob(cubes.get(0), SOURCE, renderer, bone, poseStack.last(), rb.type, jp, light, overlay, colour)
                : PartBatch.captureGeo(cubes.get(0), SOURCE, renderer, bone, poseStack.last(), buffer, light, overlay, colour);
        if (queued && VERIFY) verify(renderer, poseStack, bone);
        return queued;
    }

    // ------------------------------------------------------------------ -Dlab.geoparts.verify=true

    /** What the shader computes from geometry + record, against GeckoLib's own CPU vertices of the same bone. */
    public static final boolean VERIFY = Boolean.getBoolean("lab.geoparts.verify");
    public static long verifiedBones, posMismatch, normalMismatch, countMismatch;
    public static double maxPosDiff;
    private static final Rec CPU = new Rec(), GEO = new Rec();

    private static final class Rec implements VertexConsumer, PartBatch.Flagged {
        float[] v = new float[4096 * 6];
        int[] f = new int[4096];
        int n, flags;

        @Override
        public void sleep$flags(int fl) {
            flags = fl;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (v.length < (n + 1) * 6) v = java.util.Arrays.copyOf(v, v.length * 2);
            if (f.length < n + 1) f = java.util.Arrays.copyOf(f, f.length * 2);
            v[n * 6] = x; v[n * 6 + 1] = y; v[n * 6 + 2] = z;
            f[n] = flags;
            n++;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float w) { return this; }
        @Override public VertexConsumer setUv1(int u, int w) { return this; }
        @Override public VertexConsumer setUv2(int u, int w) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            v[(n - 1) * 6 + 3] = x; v[(n - 1) * 6 + 4] = y; v[(n - 1) * 6 + 5] = z;
            return this;
        }
    }

    private static int nb(float f) {   // BufferBuilder's normal byte
        return (byte) ((int) (net.minecraft.util.Mth.clamp(f, -1.0F, 1.0F) * 127.0F) & 0xFF);
    }

    private static void verify(GeoRenderer<?> renderer, PoseStack poseStack, GeoBone bone) {
        CPU.n = 0;
        for (GeoCube cube : bone.getCubes()) {
            poseStack.pushPose();
            renderer.renderCube(poseStack, cube, CPU, 0, 0, -1);
            poseStack.popPose();
        }
        GEO.n = 0;
        GEO.flags = 0;
        emit(renderer, bone, GEO);
        verifiedBones++;
        if (CPU.n != GEO.n) {
            countMismatch++;
            return;
        }
        Matrix4f m = poseStack.last().pose();
        org.joml.Matrix3f nm = poseStack.last().normal();
        org.joml.Vector4f p = new org.joml.Vector4f();
        Vector3f nn = new Vector3f();
        boolean pos = false, nor = false;
        for (int i = 0; i < GEO.n; i++) {
            int o = i * 6;
            m.transform(p.set(GEO.v[o], GEO.v[o + 1], GEO.v[o + 2], 1f));
            double d = Math.max(Math.abs(p.x - CPU.v[o]), Math.max(Math.abs(p.y - CPU.v[o + 1]), Math.abs(p.z - CPU.v[o + 2])));
            if (d > maxPosDiff) maxPosDiff = d;
            if (d > 1e-4) pos = true;
            // the geometry's normal as the arena keeps it (bytes), through the bone's normal matrix, packed again
            nm.transform(nn.set((byte) nb(GEO.v[o + 3]) / 127f, (byte) nb(GEO.v[o + 4]) / 127f, (byte) nb(GEO.v[o + 5]) / 127f));
            int fl = GEO.f[i];   // as the shader: abs() on a flat cube's axes
            if ((fl & 1) != 0) nn.x = Math.abs(nn.x);
            if ((fl & 2) != 0) nn.y = Math.abs(nn.y);
            if ((fl & 4) != 0) nn.z = Math.abs(nn.z);
            if (nb(nn.x) != nb(CPU.v[o + 3]) || nb(nn.y) != nb(CPU.v[o + 4]) || nb(nn.z) != nb(CPU.v[o + 5])) nor = true;
        }
        if (pos) posMismatch++;
        if (nor) normalMismatch++;
    }

    public static String report() {
        return VERIFY ? String.format(java.util.Locale.ROOT, "GeckoLib bones verified %d: vertex count differs %d, position > 1e-4 %d (max %.2e), normal bytes differ %d",
                verifiedBones, countMismatch, posMismatch, maxPosDiff, normalMismatch) : "";
    }

    /**
     * The bone-space geometry: GeoRenderer's renderCube with an identity pose (the eligible renderers use exactly it),
     * except fixInvertedFlatCube, which depends on the posed normal: a flat cube's axes go as flags (bit 0 x, 1 y, 2 z)
     * with its vertices, and the shader flips there (abs) after posing, as GeckoLib does on the CPU.
     */
    private static boolean emit(Object renderer, Object boneObj, VertexConsumer consumer) {
        GeoBone bone = (GeoBone) boneObj;
        PartBatch.Flagged flagged = consumer instanceof PartBatch.Flagged fc ? fc : null;
        org.joml.Vector4f p = new org.joml.Vector4f();
        for (GeoCube cube : bone.getCubes()) {
            var size = cube.size();
            int flags = (size.y == 0 || size.z == 0 ? 1 : 0) | (size.x == 0 || size.z == 0 ? 2 : 0) | (size.x == 0 || size.y == 0 ? 4 : 0);
            if (flags != 0 && flagged == null) return false;
            if (flagged != null) flagged.sleep$flags(flags);
            PoseStack ps = new PoseStack();
            software.bernie.geckolib.util.RenderUtil.translateToPivotPoint(ps, cube);
            software.bernie.geckolib.util.RenderUtil.rotateMatrixAroundCube(ps, cube);
            software.bernie.geckolib.util.RenderUtil.translateAwayFromPivotPoint(ps, cube);
            org.joml.Matrix3f nm = ps.last().normal();
            Matrix4f pose = new Matrix4f(ps.last().pose());
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                Vector3f normal = nm.transform(new Vector3f(quad.normal()));
                for (software.bernie.geckolib.cache.object.GeoVertex vertex : quad.vertices()) {
                    Vector3f pos = vertex.position();
                    pose.transform(p.set(pos.x(), pos.y(), pos.z(), 1.0f));
                    consumer.addVertex(p.x(), p.y(), p.z(), -1, vertex.texU(), vertex.texV(), 0, 0, normal.x(), normal.y(), normal.z());
                }
            }
        }
        if (flagged != null) flagged.sleep$flags(0);
        return true;
    }
}
