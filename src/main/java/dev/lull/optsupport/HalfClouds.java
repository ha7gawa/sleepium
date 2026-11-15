package dev.lull.optsupport;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * -Dlab.clouds.half=true (non-exact; 1.3.0 research): the clouds drawn at half the resolution and laid over the frame.
 * On the N100 fancy clouds cost 0.92 of a 3.7-ms GPU frame (two passes of large alpha-tested quads over most of the
 * screen when the camera is above them: fill-bound). The scene's depth is copied into a half-size target (nearest),
 * vanilla's two cloud passes draw there (LevelRenderer.getCloudsTarget answers with it while the clouds draw - the cloud
 * render types bind that target), and the result - premultiplied by the blending into a cleared target - is laid over
 * the frame with ONE, ONE_MINUS_SRC_ALPHA (the same as vanilla's direct blend for the one layer the depth pass leaves).
 * Edges against nearer terrain are half-resolution. Not with Fabulous (it has its own clouds target).
 */
public final class HalfClouds {
    private HalfClouds() {}

    public static final boolean ON = Boolean.getBoolean("lab.clouds.half");
    private static TextureTarget target;
    private static boolean active;
    public static long frames;

    /** LevelRenderer.getCloudsTarget while the half clouds draw: the half-size target, else null (vanilla's answer) */
    public static RenderTarget target() {
        return active ? target : null;
    }

    /** LevelRenderer.renderClouds HEAD (no Fabulous): the half target ready - cleared, the scene's depth in it, its viewport */
    public static void begin() {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        int w = Math.max(1, main.width / 2), h = Math.max(1, main.height / 2);
        if (target == null) {
            target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11C.GL_LINEAR);
        } else if (target.width != w || target.height != h) {
            target.resize(w, h, Minecraft.ON_OSX);
            target.setFilterMode(GL11C.GL_LINEAR);
        }
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear(Minecraft.ON_OSX);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, w, h, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        target.bindWrite(true);   // (its viewport: the cloud types bind it again without one)
        active = true;
    }

    /** LevelRenderer.renderClouds RETURN: the half clouds laid over the main target, the main target bound again */
    public static void end() {
        if (!active) return;
        active = false;
        frames++;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        main.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, 1f, 0f, 1f, -1f, 1f), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        RenderSystem.applyModelViewMatrix();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(0f, 0f, 0f).setUv(0f, 0f);
        bb.addVertex(1f, 0f, 0f).setUv(1f, 0f);
        bb.addVertex(1f, 1f, 0f).setUv(1f, 1f);
        bb.addVertex(0f, 1f, 0f).setUv(0f, 1f);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(proj, sorting);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
