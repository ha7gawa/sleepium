package dev.lull.ui;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** The HUD's information widgets (Hud): position, system, network, clock - read only when shown, at the HUD's redraws. */
final class HudInfo {
    private HudInfo() {}

    static String[] position() {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (p == null || mc.level == null) return new String[] {"Position: no world"};
        BlockPos b = p.blockPosition();
        String biome = mc.level.getBiome(b).unwrapKey().map(k -> k.location().getPath().replace('_', ' ')).orElse("?");
        return new String[] {
                String.format(Locale.ROOT, "XYZ %.1f / %.1f / %.1f · block %d %d %d", p.getX(), p.getY(), p.getZ(), b.getX(), b.getY(), b.getZ()),
                String.format(Locale.ROOT, "Facing %s · biome %s", p.getDirection().getName(), biome)};
    }

    private static long sysAt;
    private static String sysLine = "System: measuring...";

    static String system() {
        long now = System.nanoTime();
        if (now - sysAt < 500_000_000L) return sysLine;
        sysAt = now;
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            double game = os.getProcessCpuLoad(), all = os.getCpuLoad();
            long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
            sysLine = String.format(Locale.ROOT, "CPU game %s · all %s · RAM %.1f / %.1f GB · %d threads", pct(game), pct(all),
                    (total - free) / 1073741824.0, total / 1073741824.0, Runtime.getRuntime().availableProcessors());
        } catch (Throwable t) {
            sysLine = "System: not available on this Java";
        }
        return sysLine;
    }

    private static String pct(double v) {
        return v < 0 ? "?" : Math.round(v * 100) + " %";
    }

    static String network() {
        Minecraft mc = Minecraft.getInstance();
        var c = mc.getConnection();
        if (c == null || mc.player == null) return "Network: not connected";
        var info = c.getPlayerInfo(mc.player.getUUID());
        int ping = info == null ? -1 : info.getLatency();
        var conn = c.getConnection();
        return String.format(Locale.ROOT, "Ping %s · packets in %.0f/s · out %.0f/s%s", ping < 0 ? "?" : ping + " ms",
                conn.getAverageReceivedPackets() * 20, conn.getAverageSentPackets() * 20, mc.isLocalServer() ? " · singleplayer" : "");
    }

    static String clock() {
        Minecraft mc = Minecraft.getInstance();
        String real = java.time.LocalTime.now().withNano(0).toString();
        if (mc.level == null) return "Clock " + real;
        long t = mc.level.getDayTime();
        long day = t / 24000L + 1, tod = t % 24000L;
        int h = (int) ((tod / 1000 + 6) % 24), m = (int) (tod % 1000 * 60 / 1000);
        return String.format(Locale.ROOT, "Day %d · %02d:%02d in game · %s on your clock", day, h, m, real.length() > 5 ? real.substring(0, 5) : real);
    }
}
