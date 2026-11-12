package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * The F3 screen's vertices kept between refreshes (DebugScreenTextCacheMixin). With its lines gathered less often
 * (DebugScreenCacheMixin) laying out ~50 lines of text every frame was the screen's cost (desktop client_entities:
 * 1890 fps without F3, 986 with it, 1132 with the lines cached). Every sleep.f3.refreshMs (100 ms; the lines change no
 * more often) the overlay draws into a recorder (RenderCache's: the vertices as the font writes them, per render
 * type); every frame those bytes go into the GUI's buffers - the same vertices, no layout. A new recording when the
 * GUI's size changes or F3 reopens; the vanilla way with a chart open (they move every frame) or once a type the
 * recorder cannot keep was seen. Mods' debug lines (NeoForge's DebugText event) refresh with the recording.
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
            super(new ByteBufferBuilder(256), new java.util.LinkedHashMap<>());
        }

        @Override public VertexConsumer getBuffer(RenderType type) { return RECORDER.getBuffer(type); }
        @Override public void endBatch() {}
        @Override public void endBatch(RenderType type) {}
        @Override public void endLastBatch() {}
    }

    /**
     * DebugScreenOverlay.render around draw (the original into g) and drawInto (the original into another GuiGraphics):
     * replayed from the recording when it is fresh, else recorded anew and replayed.
     */
    public static void render(DebugScreenOverlay overlay, GuiGraphics g, Runnable draw, java.util.function.Consumer<GuiGraphics> drawInto) {
        if (!overlay.showDebugScreen() || overlay.showFpsCharts() || overlay.showProfilerChart() || overlay.showNetworkCharts() || broken) {
            valid = false;
            draw.run();
            return;
        }
        long now = System.nanoTime();
        int w = g.guiWidth(), h = g.guiHeight();
        if (!valid || now - at >= REFRESH || w != width || h != height) {
            if (source == null) source = new RecordingSource();
            GuiGraphics rg = new GuiGraphics(Minecraft.getInstance(), source);
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
