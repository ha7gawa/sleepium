package dev.lull.ui;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;

/**
 * Live numbers for the settings screen's overview and the optional in-game corner: frame times from a ring of the
 * last 240 frames (render thread), the integrated server's tick time and the sleep engine's snapshot (server thread,
 * once a second, published through volatiles), the shaderpack's state.
 */
public final class SleepStats {
    private SleepStats() {}

    private static final long[] FRAMES = new long[240];
    private static final long[] SORT = new long[240];
    private static int frameN, frameAt;
    private static long lastFrame;

    /** render thread, every frame */
    static void frame() {
        long now = System.nanoTime();
        if (lastFrame != 0) {
            FRAMES[frameAt] = now - lastFrame;
            frameAt = (frameAt + 1) % FRAMES.length;
            if (frameN < FRAMES.length) frameN++;
        }
        lastFrame = now;
    }

    /** {p50, p99} of the ring in ms */
    static double[] frameMs() {
        if (frameN == 0) return new double[] {0, 0};
        System.arraycopy(FRAMES, 0, SORT, 0, frameN);
        java.util.Arrays.sort(SORT, 0, frameN);
        return new double[] {SORT[frameN / 2] / 1e6, SORT[Math.min(frameN - 1, (int) (frameN * 0.99))] / 1e6};
    }

    // the server thread's snapshot (integrated server only)
    static volatile double mspt = -1;
    static volatile long tracked, asleep, skippedPerSecond;
    private static long lastSkipped = -1, serverTicks;

    /** server thread, every tick (ServerTickEvent.Post) */
    static void serverTick(net.minecraft.server.MinecraftServer server) {
        if (++serverTicks % 20 != 0) return;
        mspt = server.getAverageTickTimeNanos() / 1e6;
        if (dev.lull.optsupport.sleep.SleepEngine.ON) {
            long[] s = dev.lull.optsupport.sleep.SleepEngine.snapshot();
            tracked = s[0];
            asleep = s[1];
            skippedPerSecond = lastSkipped < 0 ? 0 : s[2] - lastSkipped;
            lastSkipped = s[2];
        }
    }

    static boolean singleplayer() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    private static String gpu;

    static String gpu() {
        if (gpu == null) gpu = GL11.glGetString(GL11.GL_RENDERER).split("/")[0];   // "NVIDIA GeForce RTX 4070 SUPER/PCIe/SSE2": the name
        return gpu;
    }

    static String shaderState() {
        String preparing = dev.lull.shaders.ShaderPipeline.preparing();
        if (preparing != null) return Component.translatable("sleepium.ui.stats.preparing", preparing).getString();
        String name = dev.lull.shaders.ShaderPipeline.currentName();
        return name == null ? Component.translatable("sleepium.ui.stats.nopack").getString() : name;
    }

    /** the lines of the overview's live block */
    static String[] lines() {
        Minecraft mc = Minecraft.getInstance();
        double[] f = frameMs();
        String server = singleplayer() && mspt >= 0
                ? String.format(Locale.ROOT, "MSPT %.2f ms  TPS %.1f", mspt, Math.min(20.0, 1000.0 / Math.max(mspt, 1e-3)))
                : Component.translatable("sleepium.ui.stats.noserver").getString();
        String sleep = !singleplayer() ? "" : dev.lull.optsupport.sleep.SleepEngine.ON
                ? Component.translatable("sleepium.ui.stats.sleep", asleep, tracked, skippedPerSecond).getString()
                : Component.translatable("sleepium.ui.stats.sleepoff").getString();
        Runtime rt = Runtime.getRuntime();
        return new String[] {String.format(Locale.ROOT, "FPS %d   frame p50 %.2f ms  p99 %.2f ms", mc.getFps(), f[0], f[1]), server, sleep,
                Component.translatable("sleepium.ui.stats.shaders", shaderState()).getString(),
                String.format(Locale.ROOT, "%s   heap %d / %d MB", gpu(), (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20)};
    }

    /** -Dsleep.ui.hud / the overview's switch: the corner in the game */
    static volatile boolean hud = Boolean.getBoolean("sleep.ui.hud");

    static void drawHud(GuiGraphics g) {
        if (!hud || Minecraft.getInstance().getDebugOverlay().showDebugScreen()) return;
        double[] f = frameMs();
        String a = String.format(Locale.ROOT, "Sleepium  FPS %d  p99 %.1f ms", Minecraft.getInstance().getFps(), f[1]);
        String b = singleplayer() && mspt >= 0 ? String.format(Locale.ROOT, "MSPT %.1f  asleep %d", mspt, asleep) : null;
        var font = Minecraft.getInstance().font;
        int y = dev.lull.shaders.ShaderPipeline.preparing() != null ? 16 : 4;
        g.drawString(font, a, 4, y, 0xFFDDE4FF, true);
        if (b != null) {
            y += 10;
            g.drawString(font, b, 4, y, 0xFFB8C2E8, true);
        }
        String[] w = world();
        for (String s : w) {
            y += 10;
            g.drawString(font, s, 4, y, 0xFFB8C2E8, true);
        }
    }

    private static String[] worldLines = new String[0];
    private static long worldAt;

    /**
     * The F3 screen's entity and chunk lines (vanilla's own counters: entities drawn / in the level, culled, ...;
     * sections compiled / in view), for testers who open F3 only for them - gathered 4 times a second, not every frame
     */
    static String[] world() {
        long now = System.nanoTime();
        if (now - worldAt < 250_000_000L) return worldLines;
        worldAt = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return worldLines = new String[0];
        return worldLines = new String[] {mc.levelRenderer.getEntityStatistics(), mc.levelRenderer.getSectionStatistics()};
    }
}
