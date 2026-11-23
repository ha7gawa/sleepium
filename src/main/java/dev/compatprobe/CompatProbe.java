package dev.compatprobe;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * -Dsleep.test.compatProbe=true (test only, notes/100): what other mods do that decoupled frames must survive, written
 * the way those mods write it, in a package of its own (not Sleepium's).
 * 1. A render handler walks a list the client tick rebuilds (furnace_open's SlashInfinityVfx, 11-22): with the frame and
 *    the tick at once, a ConcurrentModificationException.
 * 2. Main-thread code that is "not on the render thread" queues its use of an image and closes the image at once
 *    (11-22: "Image is not allocated" in replayQueue).
 * Counts go to the log every 200 ticks; a failure is thrown on (the crash the reports show).
 */
public final class CompatProbe {
    private CompatProbe() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("compatprobe");

    private static final List<int[]> EFFECTS = new ArrayList<>();
    private static long ticks, walks, walked, images, imageUses;

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> render(e));
        LOG.info("[compatprobe] registered: a render handler walking a list the tick rebuilds; queued image uses with an immediate close");
    }

    private static void tick() {
        ticks++;
        // the list rebuilt every tick, slowly enough to be caught in the middle
        EFFECTS.clear();
        for (int i = 0; i < 4000; i++) {
            EFFECTS.add(new int[] {i, i * 31});
            if ((i & 255) == 0) Thread.onSpinWait();
        }
        // a mod's image work from the main thread: queued when "not on the render thread", the image closed at once
        if (ticks % 5 == 0) {
            NativeImage img = new NativeImage(16, 16, false);
            img.setPixelRGBA(1, 1, 0xFF00FF00);
            images++;
            if (RenderSystem.isOnRenderThread()) use(img);
            else RenderSystem.recordRenderCall(() -> use(img));
            img.close();
        }
        if (ticks % 200 == 0)
            LOG.info("[compatprobe] ticks " + ticks + ", render walks " + walks + " (" + walked + " entries), images " + images + ", image uses " + imageUses);
    }

    private static void use(NativeImage img) {
        if (img.getPixelRGBA(1, 1) != 0xFF00FF00) throw new IllegalStateException("[compatprobe] image changed");
        imageUses++;
    }

    private static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY && e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        long sum = 0;
        for (int[] x : EFFECTS) sum += x[1];   // (a for-each: modCount checked)
        walks++;
        walked += sum == 42 ? 0 : EFFECTS.size();
    }
}
