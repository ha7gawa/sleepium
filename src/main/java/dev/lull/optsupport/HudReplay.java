package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * A HUD drawn a few times a second and replayed in between (Sleepium's HUD, notes/73): the drawing goes into a
 * recorder (RenderCache's, the vertices as the font and fill write them, per render type); every frame those bytes go
 * into the GUI's buffers - no text layout. The same way as the F3 cache (HudTextCache), one instance per HUD.
 */
public final class HudReplay {
    private final RenderCache.Recorder recorder = new RenderCache.Recorder();
    private final RenderCache.Rec rec = new RenderCache.Rec();
    private Source source;
    private long at;
    private int width = -1, height = -1;
    private boolean valid, broken;

    private final class Source extends MultiBufferSource.BufferSource {
        Source() {
            super(new BufferBuilder(256), java.util.Map.of());
        }

        @Override public VertexConsumer getBuffer(RenderType type) { return recorder.getBuffer(type); }
        @Override public void endBatch() {}
        @Override public void endBatch(RenderType type) {}
        @Override public void endLastBatch() {}
    }

    /** a new drawing at the next frame */
    public void invalidate() {
        valid = false;
    }

    /** draws with {@code draw} every {@code refreshNs} (or when the GUI's size changed), else replays the last drawing */
    public void render(GuiGraphics g, long refreshNs, java.util.function.Consumer<GuiGraphics> draw) {
        if (broken) {
            draw.accept(g);
            return;
        }
        long now = System.nanoTime();
        int w = g.guiWidth(), h = g.guiHeight();
        if (!valid || now - at >= refreshNs || w != width || h != height) {
            if (source == null) source = new Source();
            GuiGraphics rg = new GuiGraphics(Minecraft.getInstance(), source);
            rg.pose().last().pose().set(g.pose().last().pose());
            rg.pose().last().normal().set(g.pose().last().normal());
            recorder.reset();
            draw.accept(rg);
            if (recorder.unsupported) {
                broken = true;
                recorder.reset();
                System.out.println("[sleep/hud] a render type the recorder cannot keep: the HUD drawn every frame");
                draw.accept(g);
                return;
            }
            recorder.finish(rec);
            valid = true;
            at = now;
            width = w;
            height = h;
        }
        g.drawManaged(() -> RenderCache.append(rec, g.bufferSource()));
    }
}
