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
 * <p>Default: the bytes in a GL buffer object. Checked against vanilla with screenshots of the client bench scene
 * (120 armour stands, 235 item frames...): identical to the pixel (NVIDIA, 11-25), 77 -> 210 fps. (An earlier finding
 * of 1 LSB on lit item frames was the bench's own noise: the client counts the time of day between the server's time
 * packets, so the sun - and the sky light on the frames - differed between runs; the bench now holds noon.)
 *
 * <p>-Dsleep.render.modelClientMemory=true: the bytes from client memory instead (vanilla's own path, a direct buffer
 * kept) - for a driver where the buffer path would differ; still saves the per-frame rebuild.
 */
public final class ModelVbo {
    private static final BufferBuilder BUILD = new BufferBuilder(1 << 16);
    static final boolean GPU = !Boolean.getBoolean("sleep.render.modelClientMemory");
    private final ByteBuffer bytes;      // client memory (-Dsleep.render.modelClientMemory)
    private final VertexBuffer vbo;      // a GL buffer (default)
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
        if (GPU) {
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
