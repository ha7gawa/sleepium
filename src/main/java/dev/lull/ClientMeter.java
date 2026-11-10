package dev.lull;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Client-side frame probe. Loaded only on the physical client (see SleepMod).
 *
 * <p>{@code /sleepclient run <seconds> <label>} records, per frame, on the render thread:
 * <ul>
 *   <li>frame   – RenderFrameEvent.Pre -> next Pre (full frame incl. client tick, swap, vsync wait)</li>
 *   <li>render  – Pre -> Post (GameRenderer.render: CPU-side world + GUI submission)</li>
 *   <li>alloc   – bytes allocated by the render thread per frame</li>
 * </ul>
 * and appends a row to {@code <gamedir>/probe/summary-client.csv}. Cross-thread task counters (render thread
 * event loop, "Section Renderer" mailbox = chunk mesh rebuilds) come from TaskStats.
 */
final class ClientMeter {
    private static final int CAPACITY = 1 << 17;   // ~36 min at 60 fps
    private static final long[] frames = new long[CAPACITY];
    /** -Dsleep.meter.frameLog=true: per frame the level's game time and the client tick that ran in it (frames-<label>.csv) */
    private static final boolean FRAME_LOG = Boolean.getBoolean("sleep.meter.frameLog");
    private static final long[] frameGameTime = FRAME_LOG ? new long[CAPACITY] : null, frameTick = FRAME_LOG ? new long[CAPACITY] : null;
    private static long lastTickNanos;
    /**
     * the frame log's other columns, per frame: client ticks run in it, the particle ticks' time (with the particle shape
     * memo's measurement on), GC time (all collectors, ms), sounds started, entities added to the client level, and the
     * integrated server's longest tick finished in it
     */
    private static final long[][] FL = FRAME_LOG ? new long[6][CAPACITY] : null;
    /** -Dsleep.meter.tickSplit=true (test mixin testmods.ticksplit): Minecraft.tick's large calls, a column each */
    private static final long[][] FLS = FRAME_LOG && testmods.meter.TickSplit.ON ? new long[testmods.meter.TickSplit.PARTS.length][CAPACITY] : null;
    private static int tickCount, sounds, added, lastServerTick = -1;
    private static long lastGcMs = -1;
    private static final java.util.List<java.lang.management.GarbageCollectorMXBean> GCS = FRAME_LOG ? java.lang.management.ManagementFactory.getGarbageCollectorMXBeans() : null;
    private static final long[] renders = new long[CAPACITY];
    private static final long[] allocs = new long[CAPACITY];
    private static final long[] ticks = new long[CAPACITY / 4];   // Minecraft.tick durations (ClientTickEvent Pre -> Post)
    private static int nt;
    private static long tickStart;
    private static int n;
    private static long lastPre, preNanos, allocAtPre;
    private static long stopAtNanos = -1, startNanos;
    private static String label = "";

    private ClientMeter() {}

    static void register(IEventBus bus) {
        if (System.getProperty("sleep.clientAuto") != null) ClientAuto.register(bus);
        // Forge 1.20.1: RenderTickEvent START / END is 1.21's RenderFrameEvent Pre / Post (both around GameRenderer.render
        // in Minecraft.runTick), ClientTickEvent START / END is ClientTickEvent Pre / Post
        bus.addListener(EventPriority.HIGHEST, false, TickEvent.RenderTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.START) onPre();
        });
        bus.addListener(EventPriority.LOWEST, false, TickEvent.RenderTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.END) onPost();
        });
        bus.addListener(EventPriority.HIGHEST, false, TickEvent.ClientTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.START) tickStart = System.nanoTime();
        });
        bus.addListener(EventPriority.LOWEST, false, TickEvent.ClientTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.END && stopAtNanos > 0 && tickStart != 0 && nt < ticks.length) ticks[nt++] = System.nanoTime() - tickStart;
            if (e.phase == TickEvent.Phase.END && FRAME_LOG && tickStart != 0) {   // a frame may run several ticks (vanilla's catch-up)
                lastTickNanos += System.nanoTime() - tickStart;
                tickCount++;
            }
            if (stopAtNanos > 0) {   // a screen over the world in the window
                var m = net.minecraft.client.Minecraft.getInstance();
                if (m.level != null && m.screen != null && !(m.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
                    screenTicks++;
                    screenName = m.screen.getClass().getSimpleName();
                }
            }
        });
        if (FRAME_LOG) {   // the frame log's counters: sounds started, entities added to the client level
            bus.addListener(EventPriority.NORMAL, false, net.minecraftforge.client.event.sound.PlaySoundEvent.class, e -> sounds++);
            bus.addListener(EventPriority.NORMAL, false, net.minecraftforge.event.entity.EntityJoinLevelEvent.class, e -> {
                if (e.getLevel().isClientSide()) added++;
            });
        }
        bus.addListener(EventPriority.NORMAL, false, RegisterClientCommandsEvent.class, e -> e.getDispatcher().register(
                Commands.literal(SleepMod.DEV ? "sleepclient" : "sleepiumclient").then(Commands.literal("run")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                .then(Commands.argument("label", StringArgumentType.word()).executes(ctx -> {
                                    start(IntegerArgumentType.getInteger(ctx, "seconds"), StringArgumentType.getString(ctx, "label"));
                                    ctx.getSource().sendSuccess(() -> Component.literal("sleepclient: recording '" + label + "'"), false);
                                    return 1;
                                }))))));
    }

    /** client ticks of the measured window with a screen over the world (the game paused or covered: not play) */
    static int screenTicks;
    static String screenName;

    static boolean running() {
        return stopAtNanos >= 0;
    }

    static void start(int seconds, String l) {
        screenTicks = 0;
        screenName = null;
        nt = 0;
        n = 0;
        lastPre = 0;
        label = l;
        TaskStats.reset();
        startNanos = System.nanoTime();
        cpuAtStart = processCpu();
        threadsAtStart = threadCpu();
        stopAtNanos = startNanos + seconds * 1_000_000_000L;
        secondAt = startNanos;
        secondFrom = 0;
    }

    /**
     * -Dsleep.clientAuto.timeline=true: a log line per second of the window (scenes that change while measured): frames
     * and the slowest frame of that second, the integrated server's mean tick over its last 20 ticks, the entities.
     */
    private static final boolean TIMELINE = Boolean.getBoolean("sleep.clientAuto.timeline");
    private static long secondAt;
    private static int secondFrom;

    private static void timelineLine(long now) {
        long worst = 0;
        for (int i = secondFrom; i < n; i++) worst = Math.max(worst, frames[i]);
        int frames1 = n - secondFrom;
        secondFrom = n;
        secondAt = now;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        double srv = Double.NaN;
        if (server != null) {
            long[] times = server.tickTimes;   // the last 100 ticks' durations, ns (read racily: a monitor)
            int tc = server.getTickCount();
            long sum = 0;
            for (int k = 1; k <= 20; k++) sum += times[Math.floorMod(tc - k, times.length)];
            srv = sum / 20 / 1e6;
        }
        SleepMod.LOGGER.info(String.format(Locale.ROOT, "sleepclient timeline '%s' t=%d fps=%d worst=%.1fms server=%.1fms entities=%d %s",
                label, Math.round((now - startNanos) / 1e9), frames1, worst / 1e6, srv,
                mc.level != null ? mc.level.getEntityCount() : -1, mc.levelRenderer.getEntityStatistics()));
    }

    private static long cpuAtStart;
    private static java.util.Map<Long, long[]> threadsAtStart = java.util.Map.of();
    private static final java.util.Map<Long, String> NAMES = new java.util.HashMap<>();

    /** CPU time per live thread (ns), names remembered */
    private static java.util.Map<Long, long[]> threadCpu() {
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        java.util.Map<Long, long[]> m = new java.util.HashMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            long c = mx.getThreadCpuTime(t.getId());
            if (c >= 0) {
                m.put(t.getId(), new long[] {c});
                NAMES.put(t.getId(), t.getName().replaceAll("\\d+", "N"));
            }
        }
        return m;
    }

    /** Java threads' CPU in the window by group, busiest first (JVM-internal threads: the rest of process CPU) */
    private static String threadReport(double secs) {
        java.util.Map<Long, long[]> now = threadCpu();
        java.util.Map<String, Long> by = new java.util.TreeMap<>();
        for (var e : now.entrySet()) {
            long[] a = threadsAtStart.get(e.getKey());
            long d = e.getValue()[0] - (a == null ? 0 : a[0]);
            by.merge(NAMES.getOrDefault(e.getKey(), "?"), d, Long::sum);
        }
        StringBuilder b = new StringBuilder("java threads (cores):");
        by.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(10)
                .forEach(e -> b.append(String.format(Locale.ROOT, " %s %.2f;", e.getKey(), e.getValue() / 1e9 / secs)));
        return b.toString();
    }

    /** CPU time of the whole process (render thread, driver threads, workers, the integrated server), ns; -1 if unknown */
    private static long processCpu() {
        return java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime() : -1;
    }

    /** the frame log's counters for frame i, taken and reset */
    private static void frameColumns(int i) {
        FL[0][i] = tickCount;
        tickCount = 0;
        FL[1][i] = dev.lull.optsupport.ParticleStats.frameNanos;
        dev.lull.optsupport.ParticleStats.frameNanos = 0;
        long gc = 0;
        for (var b : GCS) gc += Math.max(0, b.getCollectionTime());
        FL[2][i] = lastGcMs < 0 ? 0 : gc - lastGcMs;
        lastGcMs = gc;
        FL[3][i] = sounds;
        sounds = 0;
        FL[4][i] = added;
        added = 0;
        var srv = Minecraft.getInstance().getSingleplayerServer();
        long max = 0;
        if (srv != null) {   // tick t's time sits at [t % 100] once tickCount reached t
            int tc = srv.getTickCount();
            long[] tt = srv.tickTimes;
            for (int t = Math.max(lastServerTick + 1, tc - 99); lastServerTick >= 0 && t <= tc; t++) max = Math.max(max, tt[t % 100]);
            lastServerTick = tc;
        }
        FL[5][i] = max;
        if (FLS != null)
            for (int k = 0; k < FLS.length; k++) {
                FLS[k][i] = testmods.meter.TickSplit.ACC[k];
                testmods.meter.TickSplit.ACC[k] = 0;
            }
    }

    private static void onPre() {
        dev.lull.optsupport.OwnerThread.client = Thread.currentThread();
        long now = System.nanoTime();
        if (stopAtNanos < 0) {
            return;
        }
        if (lastPre != 0 && n < CAPACITY) {
            frames[n] = now - lastPre;
            if (FRAME_LOG) {
                var lv = Minecraft.getInstance().level;
                frameGameTime[n] = lv == null ? -1 : lv.getGameTime();
                frameTick[n] = lastTickNanos;
                lastTickNanos = 0;
                frameColumns(n);
            }
            allocs[n] = ThreadAlloc.supported() ? ThreadAlloc.current() - allocAtPre : 0;
            n++;
        }
        lastPre = now;
        preNanos = now;
        allocAtPre = ThreadAlloc.current();
        if (TIMELINE && now - secondAt >= 1_000_000_000L) timelineLine(now);
        if (now >= stopAtNanos) {
            stopAtNanos = -1;
            finish();
        }
    }

    private static void onPost() {
        if (stopAtNanos >= 0 && n < CAPACITY) {
            renders[n] = System.nanoTime() - preNanos;   // belongs to the frame that is completed at the next Pre
        }
        // -Dlab.rcache.spikes (the render cache's slow-frame report): the render cache is not on 1.20.1 yet (stage 3)
    }

    private static void finish() {
        Minecraft mc = Minecraft.getInstance();
        double secs = (System.nanoTime() - startNanos) / 1e9;
        long cpuNow = processCpu();
        double cpuSecs = cpuAtStart >= 0 && cpuNow >= 0 ? (cpuNow - cpuAtStart) / 1e9 : Double.NaN;
        TickTimer.Stats f = TickTimer.Stats.of(frames, n);
        TickTimer.Stats r = TickTimer.Stats.of(renders, n);
        TickTimer.Stats a = TickTimer.Stats.of(allocs, n);
        TickTimer.Stats t = TickTimer.Stats.of(ticks, nt);
        int stutters = 0;
        for (int i = 0; i < n; i++) {
            if (frames[i] > 2 * f.p50()) {
                stutters++;
            }
        }
        String sections = mc.levelRenderer.getChunkStatistics();
        String entities = mc.levelRenderer.getEntityStatistics();

        StringBuilder sb = new StringBuilder();
        sb.append("# sleep client report '").append(label).append("' ").append(LocalDateTime.now()).append('\n')
                .append(String.format(Locale.ROOT, "window %.1f s, %d frames, %.1f fps avg%n", secs, n, n / secs))
                .append(String.format(Locale.ROOT, "process CPU %.1f s (%.2f cores), %.3f ms per frame%n", cpuSecs, cpuSecs / secs, cpuSecs * 1e3 / Math.max(1, n)))
                .append(threadReport(secs)).append('\n')
                .append(mc.getLaunchedVersion()).append(", ").append(com.mojang.blaze3d.platform.GlUtil.getRenderer()).append("\n\n")
                .append("frame   ").append(f.asMillis()).append('\n')
                .append("render  ").append(r.asMillis()).append("   (GameRenderer.render, CPU side)\n")
                .append("alloc   ").append(a.asKiB().replace("/tick", "/frame")).append("   (render thread)\n")
                .append("tick    ").append(t.asMillis()).append("   (Minecraft.tick, 20 Hz)\n")
                .append(String.format(Locale.ROOT, "stutters (frame > 2x median): %d (%.2f %%)%n", stutters, 100.0 * stutters / Math.max(1, n)))
                .append(sections).append('\n').append(entities).append("\n\n")
                .append(TaskStats.report(secs, Math.max(1, n)).replace("per tick", "per frm "));
        if (net.minecraftforge.fml.ModList.get().isLoaded("geckolib") && (Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.MolangJitMixin")))
            sb.append('\n').append(dev.lull.optsupport.MolangJit.report()).append('\n');   // (the class needs GeckoLib's mclib)

        Path dir = mc.gameDirectory.toPath().resolve(dev.lull.SleepOut.ROOT);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dir.resolve("report-client-" + label + "-" + ts + ".txt");
        if (FRAME_LOG) {
            // other_ms: the frame's time outside the ticks and GameRenderer.render - queued tasks (packets), sound, swap, waits
            StringBuilder fl = new StringBuilder("frame_ms,game_time,ticks,tick_ms,render_ms,other_ms,particle_ms,alloc_kib,gc_ms,sounds,entities_added,server_ms");
            if (FLS != null) for (String p : testmods.meter.TickSplit.PARTS) fl.append(",t_").append(p).append("_ms");
            fl.append('\n');
            for (int i = 0; i < n; i++) {
                fl.append(String.format(Locale.ROOT, "%.3f,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%.3f", frames[i] / 1e6, frameGameTime[i], FL[0][i], frameTick[i] / 1e6,
                        renders[i] / 1e6, (frames[i] - renders[i] - frameTick[i]) / 1e6, FL[1][i] / 1e6, allocs[i] >> 10, FL[2][i], FL[3][i], FL[4][i], FL[5][i] / 1e6));
                if (FLS != null) for (long[] col : FLS) fl.append(String.format(Locale.ROOT, ",%.3f", col[i] / 1e6));
                fl.append('\n');
            }
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("frames-" + label + ".csv"), fl.toString());
            } catch (java.io.IOException e) {
                System.out.println("[sleep] frame log not written: " + e);
            }
        }
        Path csv = dir.resolve("summary-client.csv");
        String row = String.format(Locale.ROOT, "%s,%s,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%d%n",
                LocalDateTime.now().withNano(0), label, n, n / secs, f.p50() / 1e6, f.p99() / 1e6, f.max() / 1e6,
                r.p50() / 1e6, r.p99() / 1e6, a.mean() / 1024, a.p99() / 1024.0, stutters);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, sb.toString());
            if (!Files.exists(csv)) {
                Files.writeString(csv, "time,label,frames,fps,frame_p50,frame_p99,frame_max,render_p50,render_p99,alloc_kib_mean,alloc_kib_p99,stutters\n");
            }
            Files.writeString(csv, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write {}", file, e);
        }
        SleepMod.LOGGER.info("\n{}", sb);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                    "sleepclient '%s': %.0f fps, frame p50 %.2f / p99 %.2f ms, %.0f KiB/frame, CPU %.2f cores / %.3f ms per frame, client tick mean %.3f / p99 %.3f ms -> %s%s",
                    label, n / secs, f.p50() / 1e6, f.p99() / 1e6, a.mean() / 1024, cpuSecs / secs, cpuSecs * 1e3 / Math.max(1, n), t.mean() / 1e6, t.p99() / 1e6, file.getFileName(),
                    screenTicks > 0 ? "; A SCREEN OVER THE WORLD " + screenTicks + " ticks (" + screenName + "): not play" : "")), false);
        }
    }
}
