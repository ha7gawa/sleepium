package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * RenderCache / ParallelRender: a reusable vertex recorder writing the same bytes a BufferBuilder of the format writes
 * (the element writers of BufferBuilder, first write of an element per vertex wins, elements not in the format
 * ignored), into one native buffer kept across recordings — no BufferBuilder, MeshData or result objects per recording.
 * The 11-argument addVertex goes through the setters (VertexConsumer's default), which gives BufferBuilder's
 * fast-path bytes too (colour as RGBA bytes, packed overlay / light as two shorts, normal bytes).
 */
public final class RecordingBuilder implements VertexConsumer {
    /** one thread writes a builder at a time: its matrix helpers' vectors */
    private final org.joml.Vector3f scratch = new org.joml.Vector3f();

    /** VertexConsumer's default, the same JOML call into the builder's own vector (it made one per vertex) */
    @Override
    public VertexConsumer addVertex(org.joml.Matrix4f pose, float x, float y, float z) {
        org.joml.Vector3f v = pose.transformPosition(x, y, z, scratch);
        return this.addVertex(v.x(), v.y(), v.z());
    }

    /** VertexConsumer's default, the same JOML call into the builder's own vector (it made one per vertex) */
    @Override
    public VertexConsumer setNormal(com.mojang.blaze3d.vertex.PoseStack.Pose pose, float normalX, float normalY, float normalZ) {
        org.joml.Vector3f v = pose.transformNormal(normalX, normalY, normalZ, scratch);
        return this.setNormal(v.x(), v.y(), v.z());
    }

    final VertexFormat format;
    /** the render type this builder records (set by RenderCache.Recorder) */
    public net.minecraft.client.renderer.RenderType type;
    final int vertexSize;
    private final int posOff, colorOff, uv0Off, uv1Off, uv2Off, normalOff;
    private final int initialFill;
    long mem;
    private long cap;
    int n;
    private long v;
    private int fill;
    /** VertexTags: runs of vertices written under one state - each run's first vertex and its three ids */
    int runs;
    int[] runAt = new int[2], runTag = new int[6];
    private final int[] now = new int[3];

    private static final int COLOR = 1, UV0 = 2, UV1 = 4, UV2 = 8, NORMAL = 16;

    public RecordingBuilder(VertexFormat format) {
        this.format = format;
        this.vertexSize = format.getVertexSize();
        this.posOff = off(format, VertexFormatElement.POSITION);
        this.colorOff = off(format, VertexFormatElement.COLOR);
        this.uv0Off = off(format, VertexFormatElement.UV0);
        this.uv1Off = off(format, VertexFormatElement.UV1);
        this.uv2Off = off(format, VertexFormatElement.UV2);
        this.normalOff = off(format, VertexFormatElement.NORMAL);
        this.initialFill = (colorOff >= 0 ? COLOR : 0) | (uv0Off >= 0 ? UV0 : 0) | (uv1Off >= 0 ? UV1 : 0) | (uv2Off >= 0 ? UV2 : 0) | (normalOff >= 0 ? NORMAL : 0);
    }

    private static int off(VertexFormat f, VertexFormatElement e) {
        return f.contains(e) ? f.getOffset(e) : -1;
    }

    public void reset() {
        n = 0;
        fill = 0;
        runs = 0;
    }

    /** a new run when the state differs from the last run's */
    private void tag(int vertex) {
        VertexTags.read(now, 0);
        if (runs > 0) {
            int k = (runs - 1) * 3;
            if (runTag[k] == now[0] && runTag[k + 1] == now[1] && runTag[k + 2] == now[2]) return;
        }
        if (runs == runAt.length) {
            runAt = java.util.Arrays.copyOf(runAt, runs * 2);
            runTag = java.util.Arrays.copyOf(runTag, runs * 6);
        }
        runAt[runs] = vertex;
        System.arraycopy(now, 0, runTag, runs * 3, 3);
        runs++;
    }

    /** count whole vertices appended by the caller (BlockMeshMemo's replay): their first byte */
    long appendRaw(int count) {
        long need = (long) (n + count) * vertexSize;
        if (need > cap) {
            long c = Math.max(need, Math.max(4096, cap * 2));
            mem = mem == 0 ? MemoryUtil.nmemAlloc(c) : MemoryUtil.nmemRealloc(mem, c);
            cap = c;
        }
        long dst = mem + (long) n * vertexSize;
        if (VertexTags.ON) tag(n);
        n += count;
        v = mem + (long) (n - 1) * vertexSize;
        fill = 0;
        return dst;
    }

    /** -Dlab.prender.bulkStack=true: putBulkData as NeoForge's default (through LWJGL's MemoryStack) */
    private static final boolean BULK_STACK = Boolean.getBoolean("lab.prender.bulkStack");

    /**
     * NeoForge 1.21.1's VertexConsumer.putBulkData (vanilla's body with the quad's own alpha, applyBakedLighting and
     * applyBakedNormals) without LWJGL's MemoryStack: its ByteBuffer is only a native-order view of the quad's eight
     * ints a vertex, so getFloat(0) is Float.intBitsToFloat(v[0]) and get(12) is v[3] & 255 - the same values, the same
     * arithmetic in the same order. The stack is found through a ThreadLocal on every quad: on the render workers it was
     * the largest part of their samples (1.20.1, client_entities: the zombies' swords).
     */
    @Override
    public void putBulkData(com.mojang.blaze3d.vertex.PoseStack.Pose pose, net.minecraft.client.renderer.block.model.BakedQuad quad, float[] brightness,
                            float red, float green, float blue, float alpha, int[] lightmap, int packedOverlay, boolean readAlpha) {
        if (BULK_STACK) {
            VertexConsumer.super.putBulkData(pose, quad, brightness, red, green, blue, alpha, lightmap, packedOverlay, readAlpha);
            return;
        }
        int[] v = quad.getVertices();
        net.minecraft.core.Vec3i dir = quad.getDirection().getNormal();
        org.joml.Matrix4f m = pose.pose();
        org.joml.Vector3f normal = pose.transformNormal((float) dir.getX(), (float) dir.getY(), (float) dir.getZ(), new org.joml.Vector3f());
        int count = v.length / 8;
        int k = (int) (alpha * 255.0F);
        for (int l = 0; l < count; l++) {
            int o = l * 8;
            float x = Float.intBitsToFloat(v[o]), y = Float.intBitsToFloat(v[o + 1]), z = Float.intBitsToFloat(v[o + 2]);
            int c = v[o + 3];
            float r, g, b;
            if (readAlpha) {
                float cr = (float) (c & 255), cg = (float) (c >> 8 & 255), cb = (float) (c >> 16 & 255);
                r = cr * brightness[l] * red;
                g = cg * brightness[l] * green;
                b = cb * brightness[l] * blue;
            } else {
                r = brightness[l] * red * 255.0F;
                g = brightness[l] * green * 255.0F;
                b = brightness[l] * blue * 255.0F;
            }
            int vertexAlpha = readAlpha ? (int) ((alpha * (float) (c >>> 24 & 255) / 255.0F) * 255) : k;
            int color = net.minecraft.util.FastColor.ARGB32.color(vertexAlpha, (int) r, (int) g, (int) b);
            int baked = v[o + 6];   // applyBakedLighting: the quad's own light, the larger per part
            int light = Math.max(lightmap[l] & 0xFFFF, baked & 0xFFFF) | Math.max(lightmap[l] >> 16 & 0xFFFF, baked >>> 16 & 0xFFFF) << 16;
            float u = Float.intBitsToFloat(v[o + 4]), vv = Float.intBitsToFloat(v[o + 5]);
            org.joml.Vector3f p = m.transformPosition(x, y, z, new org.joml.Vector3f());
            int nb = v[o + 7];   // applyBakedNormals: a baked normal replaces the face's
            byte nx = (byte) nb, ny = (byte) (nb >> 8), nz = (byte) (nb >> 16);
            if (nx != 0 || ny != 0 || nz != 0) {
                normal.set(nx / 127f, ny / 127f, nz / 127f);
                normal.mul(pose.normal());
            }
            this.addVertex(p.x(), p.y(), p.z(), color, u, vv, packedOverlay, light, normal.x(), normal.y(), normal.z());
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        long need = (long) (n + 1) * vertexSize;
        if (need > cap) {
            long c = Math.max(need, Math.max(4096, cap * 2));
            mem = mem == 0 ? MemoryUtil.nmemAlloc(c) : MemoryUtil.nmemRealloc(mem, c);
            cap = c;
        }
        v = mem + (long) n * vertexSize;
        if (VertexTags.ON) tag(n);
        n++;
        fill = initialFill;
        long p = v + posOff;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        return this;
    }

    private boolean take(int bit) {
        if ((fill & bit) == 0) return false;
        fill &= ~bit;
        return true;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        if (take(COLOR)) {
            long p = v + colorOff;
            MemoryUtil.memPutByte(p, (byte) red);
            MemoryUtil.memPutByte(p + 1, (byte) green);
            MemoryUtil.memPutByte(p + 2, (byte) blue);
            MemoryUtil.memPutByte(p + 3, (byte) alpha);
        }
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {   // ARGB int, stored as RGBA bytes (BufferBuilder.putRgba)
        return setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24);
    }

    @Override
    public VertexConsumer setUv(float u, float w) {
        if (take(UV0)) {
            long p = v + uv0Off;
            MemoryUtil.memPutFloat(p, u);
            MemoryUtil.memPutFloat(p + 4, w);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int w) {
        if (dev.lull.shaders.ShaderIds.on && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {   // a shaderpack's ids, as BufferBuilder gets them
            int id = dev.lull.shaders.ShaderIds.slot1;
            u = u & 0xFF | (id & 0xFF) << 8;
            w = w & 0xFF | (id >> 8 & 0xFF) << 8;
        }
        if (take(UV1)) {
            long p = v + uv1Off;
            MemoryUtil.memPutShort(p, (short) u);
            MemoryUtil.memPutShort(p + 2, (short) w);
        }
        return this;
    }

    @Override
    public VertexConsumer setOverlay(int packed) {
        return setUv1(packed & 0xFFFF, packed >> 16 & 0xFFFF);
    }

    @Override
    public VertexConsumer setUv2(int u, int w) {
        if (dev.lull.shaders.ShaderIds.on && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
            int id = dev.lull.shaders.ShaderIds.item1;
            u = u & 0xFF | (id & 0xFF) << 8;
            w = w & 0xFF | (id >> 8 & 0xFF) << 8;
        }
        if (take(UV2)) {
            long p = v + uv2Off;
            MemoryUtil.memPutShort(p, (short) u);
            MemoryUtil.memPutShort(p + 2, (short) w);
        }
        return this;
    }

    @Override
    public VertexConsumer setLight(int packed) {
        return setUv2(packed & 0xFFFF, packed >> 16 & 0xFFFF);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (take(NORMAL)) {
            long p = v + normalOff;
            MemoryUtil.memPutByte(p, normalByte(x));
            MemoryUtil.memPutByte(p + 1, normalByte(y));
            MemoryUtil.memPutByte(p + 2, normalByte(z));
        }
        return this;
    }

    private static byte normalByte(float value) {
        return (byte) ((int) (Mth.clamp(value, -1.0F, 1.0F) * 127.0F) & 0xFF);
    }
}
