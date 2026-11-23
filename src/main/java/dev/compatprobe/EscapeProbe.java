package dev.compatprobe;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * -Dsleep.test.escape=true (a test, issue #11): a mod-like RenderLivingEvent.Post listener that draws a red line above
 * every living entity into the game's own buffer source instead of the event's - what health bar / name tag mods do.
 * Inside a render cache recording that write escapes the recorder (in the recording's identity pose).
 */
public final class EscapeProbe {
    private EscapeProbe() {}

    public static long lines;
    static final boolean CACHED = Boolean.getBoolean("sleep.test.escape.cached");
    static net.minecraft.client.renderer.MultiBufferSource.BufferSource held;

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((RenderLivingEvent.Post<?, ?> e) -> {
            // (-Dsleep.test.escape.cached=true: the source kept from the first event on, as a mod holding it in a field)
            if (CACHED && held == null) held = Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer buf = (CACHED ? held : Minecraft.getInstance().renderBuffers().bufferSource()).getBuffer(RenderType.lines());
            PoseStack.Pose p = e.getPoseStack().last();
            buf.vertex(p.pose(), 0f, 2.6f, 0f).color(255, 0, 0, 255).normal(p.normal(), 0f, 1f, 0f).endVertex();
            buf.vertex(p.pose(), 0f, 3.2f, 0f).color(255, 0, 0, 255).normal(p.normal(), 0f, 1f, 0f).endVertex();
            lines++;
        });
        System.out.println("[probe/escape] a RenderLivingEvent.Post listener writing into the game's buffer source registered");
    }
}
