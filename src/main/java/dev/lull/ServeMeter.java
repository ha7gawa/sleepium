package dev.lull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.protocol.Packet;

/**
 * What a slow server tick does to the players (notes/92, measurement only): with {@code -Dsleep.serveMeter=true}
 * <ul>
 *   <li>how long each player packet waits from the network thread to its handler on the server thread, per packet
 *       type (ServeMeterPacketMixin: PacketUtils.ensureRunningOnSameThread - its network-thread call queues the
 *       packet, its server-thread call is the handler's start);</li>
 *   <li>the server ticks (tickServer) in the same window: count, mean, p50 / p95 / max.</li>
 * </ul>
 * Reported in the {@code /sleep net} line (NetBench), the window a joining ClientAuto client starts.
 * <p>
 * Test load, any run: {@code -Dlab.serve.slowTick=MS} sleeps MS ms in every overworld tick (a slow world, the same
 * place as a mob-heavy entity phase: after the chunk source, before the tick's end), {@code -Dlab.serve.slowTickJitter=MS}
 * adds 0..MS more; {@code /sleep stall <ms>} sleeps once (a stall: a big save, a /fill, a mod's hitch). Sleeping changes
 * no world result - only when things happen.
 */
public final class ServeMeter {
    public static final boolean ON = Boolean.getBoolean("sleep.serveMeter");
    public static final int SLOW_TICK = Integer.getInteger("lab.serve.slowTick", 0), SLOW_JITTER = Integer.getInteger("lab.serve.slowTickJitter", 0);
    private static volatile int stallMs;

    private static final Map<Packet<?>, Long> QUEUED = new IdentityHashMap<>();
    private static final Map<String, long[]> WAITS = new HashMap<>();   // type -> [n, samples...] (reservoir of the first 8191)
    private static final long[] TICKS = new long[1 << 16];
    private static int tickN;
    private static long tickStart, windowStart;
    private static java.util.Random jitter = new java.util.Random(1);

    private ServeMeter() {}

    /** network thread: the packet is queued for the server thread */
    public static void queued(Packet<?> p) {
        long t = System.nanoTime();
        synchronized (QUEUED) {
            if (QUEUED.size() < 100_000) QUEUED.put(p, t);
        }
    }

    /** server thread: the packet's handler starts */
    public static void handled(Packet<?> p) {
        Long t0;
        synchronized (QUEUED) {
            t0 = QUEUED.remove(p);
        }
        if (t0 == null) return;
        long w = System.nanoTime() - t0;
        String type = p.getClass().getSimpleName();
        synchronized (WAITS) {
            long[] a = WAITS.computeIfAbsent(type, k -> new long[8192]);
            int n = (int) a[0];
            if (n < a.length - 1) a[n + 1] = w;
            a[0] = n + 1;
        }
    }

    /** ServerTickEvent.Pre */
    public static void tickStart() {
        tickStart = System.nanoTime();
    }

    private static long nextChunkLog;
    /** the per-second census summed over a window (chunkCensus) */
    private static long censusN, censusNotFull, censusAll, censusPending;

    public static String chunkCensus() {
        String s = String.format(Locale.ROOT, "census: %.1f of %.0f chunks in view not full, %.1f ready and unsent (%d samples)",
                censusN == 0 ? 0 : (double) censusNotFull / censusN, censusN == 0 ? 0 : (double) censusAll / censusN,
                censusN == 0 ? 0 : (double) censusPending / censusN, censusN);
        censusN = censusNotFull = censusAll = censusPending = 0;
        return s;
    }

    /** ServerTickEvent.Post */
    public static void tickEnd(net.minecraft.server.MinecraftServer server) {
        long now = System.nanoTime();
        if (tickStart != 0 && tickN < TICKS.length) TICKS[tickN++] = now - tickStart;
        if ((windowStart != 0 || ChunkTimeline.ON) && now >= nextChunkLog) {   // once a second: where the chunks around the first player wait
            nextChunkLog = now + 1_000_000_000L;
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            var p = players.get(0);
            var cs = p.serverLevel().getChunkSource();
            int r = server.getPlayerList().getViewDistance() - 1, cx = p.chunkPosition().x, cz = p.chunkPosition().z, notFull = 0, all = 0;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (dx * dx + dz * dz <= r * r) {
                        all++;
                        if (cs.getChunkNow(cx + dx, cz + dz) == null) notFull++;
                    }
            var s = (dev.lull.mixin.ServeMeterSenderAccessor) p.connection.chunkSender;
            censusN++;
            censusNotFull += notFull;
            censusAll += all;
            censusPending += s.sleep$pending().size();
            SleepMod.LOGGER.info("sleep: serve chunks: {} of {} in view not full, {} full and waiting to be sent, {} batches unacknowledged, desired {} a tick",
                    notFull, all, s.sleep$pending().size(), s.sleep$unacked(), s.sleep$desired());
        }
    }

    /**
     * -Dlab.serve.drainEveryMs=N (with lab.opt.ChunkHopsInTick): the test load's sleep cut into N ms slices with the
     * chunk mailboxes run between them - a long tick with drain points inside it, as a real tick's phases would give
     */
    private static final int DRAIN_EVERY = Integer.getInteger("lab.serve.drainEveryMs", 0);

    /** LevelTickEvent.Post of the overworld: the test load */
    public static void levelTick(net.minecraft.server.level.ServerLevel level) {
        int ms = SLOW_TICK + (SLOW_JITTER > 0 ? jitter.nextInt(SLOW_JITTER + 1) : 0);
        int s = stallMs;
        if (s > 0) {
            stallMs = 0;
            SleepMod.LOGGER.info("sleep: serve stall of {} ms starts", s);
            ms += s;
        }
        if (ms > 0) {
            try {
                if (DRAIN_EVERY <= 0) Thread.sleep(ms);
                else for (int left = ms; left > 0; left -= DRAIN_EVERY) {
                    Thread.sleep(Math.min(left, DRAIN_EVERY));
                    dev.lull.optsupport.ChunkHops.run(level.getServer().getAllLevels());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void stall(int ms) {
        stallMs = ms;
    }

    public static void start() {
        synchronized (WAITS) {
            WAITS.clear();
        }
        synchronized (QUEUED) {
            QUEUED.clear();
        }
        tickN = 0;
        windowStart = System.nanoTime();
    }

    private static double pct(long[] s, int n, double q) {
        return n == 0 ? 0 : s[Math.min(n - 1, (int) Math.floor(q * n))] / 1e6;
    }

    public static String report() {
        StringBuilder b = new StringBuilder();
        long[] t = java.util.Arrays.copyOf(TICKS, tickN);
        java.util.Arrays.sort(t);
        long sum = 0;
        for (long v : t) sum += v;
        double secs = (System.nanoTime() - windowStart) / 1e9;
        b.append(String.format(Locale.ROOT, "serve: %d ticks in %.1f s (%.1f tps), tick mean %.1f p50 %.1f p95 %.1f max %.1f ms; queue wait",
                t.length, secs, t.length / secs, t.length == 0 ? 0 : sum / 1e6 / t.length, pct(t, t.length, 0.5), pct(t, t.length, 0.95), pct(t, t.length, 1.0)));
        ArrayList<String> types = new ArrayList<>();
        synchronized (WAITS) {
            types.addAll(WAITS.keySet());
            types.sort((x, y) -> Long.compare(WAITS.get(y)[0], WAITS.get(x)[0]));
            for (String k : types) {
                long[] a = WAITS.get(k);
                int n = (int) Math.min(a[0], a.length - 1);
                long[] s = java.util.Arrays.copyOfRange(a, 1, n + 1);
                java.util.Arrays.sort(s);
                b.append(String.format(Locale.ROOT, " %s n=%d p50 %.1f p95 %.1f max %.1f;", k.replace("Serverbound", "").replace("Packet", ""), a[0],
                        pct(s, n, 0.5), pct(s, n, 0.95), pct(s, n, 1.0)));
            }
        }
        return b.toString();
    }
}
