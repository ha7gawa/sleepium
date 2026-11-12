package dev.lull.optsupport;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.BufferVertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

/**
 * RenderCache / ParallelRender: a reusable vertex recorder writing the same bytes a BufferBuilder of the format writes,
 * into one native buffer kept across recordings — no BufferBuilder or RenderedBuffer per recording.
 *
 * <p>1.20.1's BufferBuilder fills a vertex element by element in the format's order: each setter writes only when the
 * current element is its own (else it does nothing), nextElement steps over padding and puts the default colour when
 * one is set, endVertex wants every element filled, lines modes write each vertex twice. This class is the same state
 * machine: it is a BufferVertexConsumer, so the element writers are vanilla's own default methods, and nextElement /
 * endVertex / color / the 14-argument fast path (NEW_ENTITY and BLOCK) are BufferBuilder's bodies. Padding bytes are
 * never written (as in BufferBuilder): RenderCache compares and copies them as they are.
 */
public final class RecordingBuilder extends DefaultedVertexConsumer implements BufferVertexConsumer {
    /** one thread writes a builder at a time: its matrix helpers' vectors */
    private final org.joml.Vector3f scratch = new org.joml.Vector3f();
    private final org.joml.Vector4f scratch4 = new org.joml.Vector4f();

    /** -Dlab.prender.bulkStack=true: putBulkData as Forge's default (through LWJGL's MemoryStack) */
    private static final boolean BULK_STACK = Boolean.getBoolean("lab.prender.bulkStack");

    /**
     * Forge 1.20.1's VertexConsumer.putBulkData (vanilla's body with applyBakedLighting, applyBakedNormals and the
     * vertex alpha) without LWJGL's MemoryStack: its ByteBuffer is only a native-order view of the quad's eight ints a
     * vertex, so getFloat(0) is Float.intBitsToFloat(v[0]) and get(12) is v[3] & 255 - the same values, the same
     * arithmetic in the same order. The stack is found through a ThreadLocal on every quad: on the render workers (many
     * thread locals in their maps) its lookup was 42 % of their samples in client_entities (the zombies' swords).
     */
    @Override
    public void putBulkData(com.mojang.blaze3d.vertex.PoseStack.Pose pose, net.minecraft.client.renderer.block.model.BakedQuad quad, float[] brightness,
                            float red, float green, float blue, float alpha, int[] lights, int overlay, boolean readExistingColor) {
        if (BULK_STACK) {
            super.putBulkData(pose, quad, brightness, red, green, blue, alpha, lights, overlay, readExistingColor);
            return;
        }
        int[] v = quad.getVertices();
        net.minecraft.core.Vec3i dir = quad.getDirection().getNormal();
        org.joml.Matrix4f m = pose.pose();
        org.joml.Vector3f normal = pose.normal().transform(new org.joml.Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
        int count = v.length / 8;
        for (int k = 0; k < count; k++) {
            int o = k * 8;
            float x = Float.intBitsToFloat(v[o]), y = Float.intBitsToFloat(v[o + 1]), z = Float.intBitsToFloat(v[o + 2]);
            int c = v[o + 3];
            float r, g, b;
            if (readExistingColor) {
                float cr = (float) (c & 255) / 255.0F, cg = (float) (c >> 8 & 255) / 255.0F, cb = (float) (c >> 16 & 255) / 255.0F;
                r = cr * brightness[k] * red;
                g = cg * brightness[k] * green;
                b = cb * brightness[k] * blue;
            } else {
                r = brightness[k] * red;
                g = brightness[k] * green;
                b = brightness[k] * blue;
            }
            int baked = v[o + 6];   // applyBakedLighting: the quad's own light, the larger per part
            int light = Math.max(lights[k] & 0xFFFF, baked & 0xFFFF) | Math.max(lights[k] >> 16 & 0xFFFF, baked >>> 16 & 0xFFFF) << 16;
            float u = Float.intBitsToFloat(v[o + 4]), vv = Float.intBitsToFloat(v[o + 5]);
            org.joml.Vector4f p = m.transform(new org.joml.Vector4f(x, y, z, 1.0F));
            int nb = v[o + 7];   // applyBakedNormals: a baked normal replaces the face's
            byte nx = (byte) nb, ny = (byte) (nb >> 8), nz = (byte) (nb >> 16);
            if (nx != 0 || ny != 0 || nz != 0) {
                normal.set(nx / 127f, ny / 127f, nz / 127f);
                normal.mul(pose.normal());
            }
            float a = readExistingColor ? alpha * (float) (c >>> 24 & 255) / 255.0F : alpha;
            this.vertex(p.x(), p.y(), p.z(), r, g, b, a, u, vv, overlay, light, normal.x(), normal.y(), normal.z());
        }
    }

    /** VertexConsumer's default, the same JOML call into the builder's own vector (it made one per vertex) */
    @Override
    public VertexConsumer vertex(org.joml.Matrix4f matrix, float x, float y, float z) {
        org.joml.Vector4f v = matrix.transform(scratch4.set(x, y, z, 1.0F));
        return this.vertex((double) v.x(), (double) v.y(), (double) v.z());
    }

    /** VertexConsumer's default, the same JOML call into the builder's own vector (it made one per vertex) */
    @Override
    public VertexConsumer normal(org.joml.Matrix3f matrix, float x, float y, float z) {
        org.joml.Vector3f v = matrix.transform(scratch.set(x, y, z));
        return this.normal(v.x(), v.y(), v.z());
    }

    final VertexFormat format;
    final VertexFormat.Mode mode;
    /** the render type this builder records (set by RenderCache.Recorder) */
    public net.minecraft.client.renderer.RenderType type;
    final int vertexSize;
    private final ImmutableList<VertexFormatElement> elements;
    private final boolean fastFormat, fullFormat, lines;
    long mem;
    private long cap;
    int n;
    /** BufferBuilder's nextElementByte: where the current element goes */
    private int next;
    private VertexFormatElement current;
    private int elementIndex;
    /** VertexTags: runs of vertices written under one state - each run's first vertex and its three ids */
    int runs;
    int[] runAt = new int[2], runTag = new int[6];
    private final int[] now = new int[3];

    public RecordingBuilder(VertexFormat format, VertexFormat.Mode mode) {
        this.format = format;
        this.mode = mode;
        this.vertexSize = format.getVertexSize();
        this.elements = format.getElements();
        this.fastFormat = format == DefaultVertexFormat.NEW_ENTITY || format == DefaultVertexFormat.BLOCK;
        this.fullFormat = format == DefaultVertexFormat.NEW_ENTITY;
        this.lines = mode == VertexFormat.Mode.LINES || mode == VertexFormat.Mode.LINE_STRIP;
        reset();
    }

    /** The element's byte offset in the format, or -1 (1.20.1's VertexFormat has offsets by index only). */
    static int offset(VertexFormat f, VertexFormatElement e) {
        ImmutableList<VertexFormatElement> es = f.getElements();
        for (int i = 0; i < es.size(); i++) if (es.get(i).equals(e)) return f.getOffset(i);
        return -1;
    }

    /** a new recording (BufferBuilder.begin: the first element, nothing written) */
    public void reset() {
        n = 0;
        next = 0;
        elementIndex = 0;
        current = elements.get(0);
        runs = 0;
        ensure(vertexSize);
    }

    private void ensure(long bytes) {
        if (bytes <= cap) return;
        long c = Math.max(bytes, Math.max(4096, cap * 2));
        mem = mem == 0 ? MemoryUtil.nmemAlloc(c) : MemoryUtil.nmemRealloc(mem, c);
        cap = c;
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
        if (elementIndex != 0) throw new IllegalStateException("Not filled all elements of the vertex");
        ensure((long) (n + count + 1) * vertexSize);
        long dst = mem + next;
        if (VertexTags.ON) tag(n);
        n += count;
        next += count * vertexSize;
        return dst;
    }

    // ------------------------------------------------------------------ BufferVertexConsumer: the element cursor

    @Override
    public VertexFormatElement currentElement() {
        return current;
    }

    @Override
    public void nextElement() {   // BufferBuilder.nextElement
        elementIndex = (elementIndex + 1) % elements.size();
        next += current.getByteSize();
        current = elements.get(elementIndex);
        if (current.getUsage() == VertexFormatElement.Usage.PADDING) nextElement();
        if (defaultColorSet && current.getUsage() == VertexFormatElement.Usage.COLOR) BufferVertexConsumer.super.color(defaultR, defaultG, defaultB, defaultA);
    }

    @Override
    public void putByte(int index, byte value) {
        MemoryUtil.memPutByte(mem + next + index, value);
    }

    @Override
    public void putShort(int index, short value) {
        MemoryUtil.memPutShort(mem + next + index, value);
    }

    @Override
    public void putFloat(int index, float value) {
        MemoryUtil.memPutFloat(mem + next + index, value);
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        if (VertexTags.ON && elementIndex == 0 && current.getUsage() == VertexFormatElement.Usage.POSITION) tag(n);
        return BufferVertexConsumer.super.vertex(x, y, z);
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {   // BufferBuilder.color
        if (defaultColorSet) throw new IllegalStateException();
        return BufferVertexConsumer.super.color(red, green, blue, alpha);
    }

    @Override
    public void endVertex() {   // BufferBuilder.endVertex
        if (elementIndex != 0) throw new IllegalStateException("Not filled all elements of the vertex");
        n++;
        ensure((long) next + vertexSize);
        if (lines) {
            MemoryUtil.memCopy(mem + next - vertexSize, mem + next, vertexSize);
            next += vertexSize;
            n++;
            ensure((long) next + vertexSize);
        }
    }

    /** a shaderpack's ids, as BufferBuilder gets them (ShaderVertexIdsMixin) */
    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        if (dev.lull.shaders.ShaderIds.on && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
            int id = dev.lull.shaders.ShaderIds.slot1;
            u = u & 0xFF | (id & 0xFF) << 8;
            v = v & 0xFF | (id >> 8 & 0xFF) << 8;
        }
        return BufferVertexConsumer.super.overlayCoords(u, v);
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        if (dev.lull.shaders.ShaderIds.on && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
            int id = dev.lull.shaders.ShaderIds.item1;
            u = u & 0xFF | (id & 0xFF) << 8;
            v = v & 0xFF | (id >> 8 & 0xFF) << 8;
        }
        return BufferVertexConsumer.super.uv2(u, v);
    }

    @Override
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float texU, float texV, int overlayUV, int lightmapUV,
            float normalX, float normalY, float normalZ) {   // BufferBuilder's fast path
        if (defaultColorSet) throw new IllegalStateException();
        if (dev.lull.shaders.ShaderIds.on && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY) {
            overlayUV = dev.lull.shaders.ShaderIds.overlay(overlayUV);
            lightmapUV = dev.lull.shaders.ShaderIds.light(lightmapUV);
        }
        if (!fastFormat) {
            super.vertex(x, y, z, red, green, blue, alpha, texU, texV, overlayUV, lightmapUV, normalX, normalY, normalZ);
            return;
        }
        if (VertexTags.ON) tag(n);
        putFloat(0, x);
        putFloat(4, y);
        putFloat(8, z);
        putByte(12, (byte) ((int) (red * 255.0F)));
        putByte(13, (byte) ((int) (green * 255.0F)));
        putByte(14, (byte) ((int) (blue * 255.0F)));
        putByte(15, (byte) ((int) (alpha * 255.0F)));
        putFloat(16, texU);
        putFloat(20, texV);
        int i;
        if (fullFormat) {
            putShort(24, (short) (overlayUV & 0xFFFF));
            putShort(26, (short) (overlayUV >> 16 & 0xFFFF));
            i = 28;
        } else {
            i = 24;
        }
        putShort(i, (short) (lightmapUV & 0xFFFF));
        putShort(i + 2, (short) (lightmapUV >> 16 & 0xFFFF));
        putByte(i + 4, BufferVertexConsumer.normalIntValue(normalX));
        putByte(i + 5, BufferVertexConsumer.normalIntValue(normalY));
        putByte(i + 6, BufferVertexConsumer.normalIntValue(normalZ));
        next += i + 8;
        endVertex();
    }
}
