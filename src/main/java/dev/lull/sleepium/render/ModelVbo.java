package dev.lull.sleepium.render;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL11;

/**
 * A model's vertices built once: the same bytes vanilla builds every frame (the same BufferBuilder calls), drawn the
 * way vanilla's draw does it - the attribute pointers set exactly as Forge's preDraw / postDraw set them for a
 * Tessellator draw (types, strides, offsets), one glDrawArrays.
 *
 * <p>Default: from client memory (a direct buffer kept) - vanilla's own path, the frame identical to vanilla's to the
 * pixel (client bench screenshots, NVIDIA, 11-25): 78 -> 116 fps. Saved: the per-frame rebuild of the vertices and, for
 * block models, one draw per quad.
 *
 * <p>-Dsleep.render.modelGpuBuffers=true: the bytes in a GL buffer object - 210 fps, but NOT exact: lit items and item
 * frames come out 1 LSB apart on ~5000 pixels (the GPU fetches a buffer's byte normals as c/127, matched to the pixel
 * by float normals widened that way; the driver's client-memory path gives other values, and widening by (2c+1)/255
 * does not reproduce them either). -Dsleep.render.normalRule=old|new (measuring) widens the normals to floats.
 */
public final class ModelVbo {
    private static final BufferBuilder BUILD = new BufferBuilder(1 << 16);
    static final boolean GPU = Boolean.getBoolean("sleep.render.modelGpuBuffers");
    /** (measuring) GPU buffers with the byte normals widened to floats by a rule: "old" (2c+1)/255, "new" max(c/127, -1) */
    static final String NORMAL_RULE = System.getProperty("sleep.render.normalRule", "");
    private static VertexFormat floatNormals;

    /** the format with its byte normal as 3 floats and without the normal's pad byte */
    private static VertexFormat floatNormalFormat(VertexFormat f) {
        if (floatNormals != null) return floatNormals;
        VertexFormat n = new VertexFormat();
        for (VertexFormatElement e : f.getElements()) {
            if (e.getUsage() == VertexFormatElement.EnumUsage.PADDING) continue;
            n.addElement(e.getUsage() == VertexFormatElement.EnumUsage.NORMAL
                    ? new VertexFormatElement(0, VertexFormatElement.EnumType.FLOAT, VertexFormatElement.EnumUsage.NORMAL, 3) : e);
        }
        return floatNormals = n;
    }

    private static float widen(byte c) {
        return NORMAL_RULE.equals("old") ? (2 * c + 1) / 255f : Math.max(c / 127f, -1f);
    }
    private final ByteBuffer bytes;      // client memory (default, exact)
    private final VertexBuffer vbo;      // a GL buffer (-Dsleep.render.modelGpuBuffers, not exact)
    private final VertexFormat format;
    private final int mode, vertices;

    private ModelVbo(ByteBuffer bytes, VertexBuffer vbo, VertexFormat format, int mode, int vertices) {
        this.bytes = bytes;
        this.vbo = vbo;
        this.format = format;
        this.mode = mode;
        this.vertices = vertices;
    }

    /** the vertices that fill(builder) writes after builder.begin(mode, format) */
    public static ModelVbo build(int mode, VertexFormat format, Consumer<BufferBuilder> fill) {
        BUILD.begin(mode, format);
        fill.accept(BUILD);
        BUILD.finishDrawing();
        int n = BUILD.getVertexCount();
        ByteBuffer src = BUILD.getByteBuffer();
        src.position(0);
        src.limit(n * format.getSize());
        ModelVbo m;
        if (GPU && !NORMAL_RULE.isEmpty() && format.hasNormal()) {
            VertexFormat nf = floatNormalFormat(format);
            ByteBuffer w = GLAllocation.createDirectByteBuffer(n * nf.getSize());
            int no = format.getNormalOffset(), so = format.getSize();
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < no; k++) w.put(src.get(i * so + k));
                for (int k = 0; k < 3; k++) w.putFloat(widen(src.get(i * so + no + k)));
                for (int k = no + 4; k < so; k++) w.put(src.get(i * so + k));
            }
            w.flip();
            VertexBuffer v = new VertexBuffer(nf);
            v.bufferData(w);
            m = new ModelVbo(null, v, nf, mode, n);
        } else if (GPU) {
            VertexBuffer v = new VertexBuffer(format);
            v.bufferData(src);
            m = new ModelVbo(null, v, format, mode, n);
        } else {
            ByteBuffer copy = GLAllocation.createDirectByteBuffer(src.remaining());
            copy.put(src);
            copy.flip();
            m = new ModelVbo(copy, null, format, mode, n);
        }
        BUILD.reset();
        return m;
    }

    public void draw() {
        if (vertices == 0) return;   // (vanilla's draw of an empty buffer issues nothing)
        int stride = format.getSize();
        List<VertexFormatElement> els = format.getElements();
        if (vbo != null) vbo.bindBuffer();
        for (int j = 0; j < els.size(); j++) {
            VertexFormatElement e = els.get(j);
            int off = format.getOffset(j);
            int type = e.getType().getGlConstant(), count = e.getElementCount();
            if (bytes != null) bytes.position(off);
            switch (e.getUsage()) {
                case POSITION -> {
                    if (bytes != null) GlStateManager.glVertexPointer(count, type, stride, bytes);
                    else GL11.glVertexPointer(count, type, stride, off);
                    GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                }
                case NORMAL -> {
                    if (bytes != null) GlStateManager.glNormalPointer(type, stride, bytes);
                    else GL11.glNormalPointer(type, stride, off);
                    GlStateManager.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                }
                case COLOR -> {
                    if (bytes != null) GlStateManager.glColorPointer(count, type, stride, bytes);
                    else GL11.glColorPointer(count, type, stride, off);
                    GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);
                }
                case UV -> {
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit + e.getIndex());
                    if (bytes != null) GlStateManager.glTexCoordPointer(count, type, stride, bytes);
                    else GL11.glTexCoordPointer(count, type, stride, off);
                    GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                }
                default -> { }
            }
        }
        GlStateManager.glDrawArrays(mode, 0, vertices);
        if (vbo != null) vbo.unbindBuffer();   // before anything draws from client arrays again
        for (int j = 0; j < els.size(); j++) {
            VertexFormatElement e = els.get(j);
            switch (e.getUsage()) {
                case POSITION -> GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
                case NORMAL -> GlStateManager.glDisableClientState(GL11.GL_NORMAL_ARRAY);
                case COLOR -> {
                    GlStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
                    GlStateManager.resetColor();
                }
                case UV -> {
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit + e.getIndex());
                    GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                }
                default -> { }
            }
        }
    }

    public void delete() {
        if (vbo != null) vbo.deleteGlBuffers();
    }
}
