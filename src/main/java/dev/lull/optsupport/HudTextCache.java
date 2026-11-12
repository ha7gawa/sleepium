package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * The F3 text's vertices kept between refreshes (ForgeHudTextBatchMixin). Forge draws the screen's lines itself
 * (ForgeGui.renderHUDText) - after its lines were gathered less often (DebugScreenCacheMixin) laying out ~2000 glyphs
 * every frame was the screen's cost (desktop, F3 open: 12 % of the render thread, ~0.5 ms a frame). Every
 * sleep.f3.refreshMs (100 ms; the lines change no more often) the method draws into a recorder (RenderCache's: the
 * vertices as the font writes them, per render type); every frame those bytes go into the GUI's buffers - the same
 * vertices, no layout. A new recording when the GUI's size changes or F3 reopens; a type the recorder cannot keep
 * draws the vanilla way from then on.
 */
public final class HudTextCache {
    private HudTextCache() {}

    private static final long REFRESH = Math.max(0L, Long.getLong("sleep.f3.refreshMs", 100L)) * 1_000_000L;
    private static final RenderCache.Recorder RECORDER = new RenderCache.Recorder();
    private static final RenderCache.Rec REC = new RenderCache.Rec();
    private static RecordingSource source;
    private static long at;
    private static int width = -1, height = -1;
    private static boolean valid, broken;
    public static long recorded, replayed;

    /** the buffer source a recording GuiGraphics draws into: every type to the recorder, no flush */
    static final class RecordingSource extends MultiBufferSource.BufferSource {
        RecordingSource() {
            super(new BufferBuilder(256), java.util.Map.of());
        }

        @Override public VertexConsumer getBuffer(RenderType type) { return RECORDER.getBuffer(type); }
        @Override public void endBatch() {}
        @Override public void endBatch(RenderType type) {}
        @Override public void endLastBatch() {}
    }

    /**
     * renderHUDText around draw (the original): replayed from the recording when it is fresh, else recorded anew and
     * replayed; the original straight into g with F3 closed (mods' lines may show then) or after a failed recording.
     */
    public static void render(GuiGraphics g, Runnable draw, java.util.function.Consumer<GuiGraphics> drawInto) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.renderDebug || broken) {
            valid = false;
            g.drawManaged(draw);
            return;
        }
        long now = System.nanoTime();
        int w = g.guiWidth(), h = g.guiHeight();
        if (!valid || now - at >= REFRESH || w != width || h != height) {
            if (source == null) source = new RecordingSource();
            GuiGraphics rg = new GuiGraphics(mc, source);
            rg.pose().last().pose().set(g.pose().last().pose());
            rg.pose().last().normal().set(g.pose().last().normal());
            RECORDER.reset();
            drawInto.accept(rg);
            if (RECORDER.unsupported) {
                broken = true;
                valid = false;
                RECORDER.reset();
                System.out.println("[sleep/opt] F3 text cache: a render type the recorder cannot keep, F3 drawn the vanilla way");
                return;   // (this frame's lines are lost: the next frame draws them)
            }
            RECORDER.finish(REC);
            valid = true;
            at = now;
            width = w;
            height = h;
            recorded++;
        } else {
            replayed++;
        }
        g.drawManaged(() -> RenderCache.append(REC, g.bufferSource()));
    }
}
