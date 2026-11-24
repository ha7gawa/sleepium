package dev.compatprobe;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * -Dsleep.test.escape=true (a test, issue #11): a mod-like RenderLivingEvent.Post listener that draws a red line above
 * every living entity into the game's own buffer source instead of the event's - what health bar / name tag mods do.
 * Inside a render cache recording that write escapes the recorder (in the recording's identity pose).
 */
public final class EscapeProbe {
    private EscapeProbe() {}

    public static long lines;
    static final boolean BADQUAD = Boolean.getBoolean("sleep.test.escape.badquad");

    public static void register() {
        NeoForge.EVENT_BUS.addListener((RenderLivingEvent.Post<?, ?> e) -> {
            if (BADQUAD) {   // (.badquad=true: a quad into the game's own source - escaping, but a type the cache records)
                VertexConsumer q = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderType.entityCutoutNoCull(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS));
                PoseStack.Pose qp = e.getPoseStack().last();
                float[][] v = {{-0.1f, 2.6f, 0f}, {0.1f, 2.6f, 0f}, {0.1f, 2.8f, 0f}, {-0.1f, 2.8f, 0f}};
                for (float[] c : v)
                    q.addVertex(qp, c[0], c[1], c[2]).setColor(255, 0, 0, 255).setUv(0f, 0f).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(e.getPackedLight()).setNormal(qp, 0f, 0f, 1f);
                lines++;
                return;
            }
            VertexConsumer buf = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderType.lines());
            PoseStack.Pose p = e.getPoseStack().last();
            buf.addVertex(p, 0f, 2.6f, 0f).setColor(255, 0, 0, 255).setNormal(p, 0f, 1f, 0f);
            buf.addVertex(p, 0f, 3.2f, 0f).setColor(255, 0, 0, 255).setNormal(p, 0f, 1f, 0f);
            lines++;
        });
        System.out.println("[probe/escape] a RenderLivingEvent.Post listener writing into the game's buffer source registered");
    }
}
