package dev.lull.optsupport;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Sleepium's HUD drawn into a texture a few times a second (notes/73): between redraws a frame draws only the HUD's
 * boxes from that texture - one textured quad per corner, no text, no vertex replay (on an Intel N100 the replay of
 * the recorded vertices was ~70 us a frame). The GUI's blending leaves premultiplied colours in a cleared target
 * (colour by source alpha, alpha by one), so the boxes go onto the screen with (one, one minus source alpha) and look as
 * when drawn directly. Any failure: drawn directly from then on.
 */
public final class HudTexture {
    private TextureTarget target;
    private long at;
    private boolean valid, broken;
    private final List<int[]> rects = new ArrayList<>();

    public void invalidate() {
        valid = false;
    }

    public boolean broken() {
        return broken;
    }

    /** draw paints into the GuiGraphics given and adds the rectangles it covered (GUI coordinates: x0, y0, x1, y1) */
    public void render(GuiGraphics g, long refreshNs, java.util.function.BiConsumer<GuiGraphics, List<int[]>> draw) {
        Minecraft mc = Minecraft.getInstance();
        if (broken) {
            rects.clear();
            draw.accept(g, rects);
            return;
        }
        long now = System.nanoTime();
        try {
            int fw = mc.getWindow().getWidth(), fh = mc.getWindow().getHeight();
            if (target == null || target.width != fw || target.height != fh) {
                if (target != null) target.destroyBuffers();
                target = new TextureTarget(fw, fh, false, Minecraft.ON_OSX);
                target.setClearColor(0, 0, 0, 0);
                valid = false;
            }
            if (!valid || now - at >= refreshNs) {
                g.flush();
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                GuiGraphics tg = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                tg.pose().last().pose().set(g.pose().last().pose());
                rects.clear();
                draw.accept(tg, rects);
                tg.flush();
                mc.getMainRenderTarget().bindWrite(true);
                valid = true;
                at = now;
            }
            blit(g);
        } catch (Throwable t) {
            broken = true;
            mc.getMainRenderTarget().bindWrite(true);
            System.out.println("[sleep/hud] the HUD's texture: " + t + " - drawn directly from now on");
        }
    }

    private void blit(GuiGraphics g) {
        if (rects.isEmpty()) return;
        g.flush();
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);   // premultiplied
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        float w = target.width, h = target.height;
        for (int[] r : rects) {
            float u0 = (float) (r[0] * scale / w), u1 = (float) (r[2] * scale / w);
            float v0 = 1f - (float) (r[1] * scale / h), v1 = 1f - (float) (r[3] * scale / h);   // the texture's rows go up
            b.addVertex(m, r[0], r[3], 0).setUv(u0, v1);
            b.addVertex(m, r[2], r[3], 0).setUv(u1, v1);
            b.addVertex(m, r[2], r[1], 0).setUv(u1, v0);
            b.addVertex(m, r[0], r[1], 0).setUv(u0, v0);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
