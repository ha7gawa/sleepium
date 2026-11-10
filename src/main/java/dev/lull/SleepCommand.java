package dev.lull;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * /sleep on 1.20.1: what the test tools use - reseed and the region hash (tools/exactness.py), the network
 * measurement and the bulk transfer's self test, the recordings (start / stop / run / status: tick stats and events
 * per type, reports in the game directory's measurement folder), the event bus benches (bench, busbench), events,
 * world, threads - and, in dev runs or with -Dsleep.tickCommand=true, the test tools' /tick (TickControl). More of
 * the 1.21.1 engine's subcommands come over with the features they report on.
 */
public final class SleepCommand {
    private SleepCommand() {}

    /** /tick on 1.20.1 is ours: only in dev runs (the tools) or when asked */
    private static final boolean TICK = !net.minecraftforge.fml.loading.FMLEnvironment.production || Boolean.getBoolean("sleep.tickCommand");

    static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        // /sleepium as on 1.21.1; /sleep kept for now (tools and players use it in production; removing it: the user's call)
        for (String name : new String[] {"sleepium", "sleep"}) d.register(Commands.literal(name).requires(s -> s.hasPermission(2))
                .then(Commands.literal("start").executes(SleepCommand::start))
                .then(Commands.literal("stop").executes(SleepCommand::stop))
                .then(Commands.literal("run")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                .then(Commands.argument("label", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "seconds"),
                                                StringArgumentType.getString(ctx, "label"))))))
                .then(Commands.literal("status").executes(SleepCommand::status))
                .then(Commands.literal("sleep").executes(ctx -> {   // the sleep engine: block entities and entities asleep
                    String r = dev.lull.optsupport.sleep.SleepEngine.report() + dev.lull.optsupport.sleep.EntitySleep.report()
                            + dev.lull.optsupport.sleep.EntitySleep.census(ctx.getSource().getLevel());
                    SleepMod.LOGGER.info("{}{}", System.lineSeparator(), r);
                    say(ctx, r);
                    return 1;
                }))
                .then(Commands.literal("sleepents").executes(ctx -> {
                    String r = dev.lull.optsupport.sleep.SleepEngine.entityReport(ctx.getSource().getLevel());
                    SleepMod.LOGGER.info("{}{}", System.lineSeparator(), r);
                    return 1;
                }))
                .then(Commands.literal("busbench").executes(ctx -> {
                    if (EventCounter.INSTANCE.enabled()) {
                        say(ctx, "sleep: run /sleep stop first");
                        return 0;
                    }
                    try {
                        // 1.21.1: BusEquivalence (dispatcher vs bus); on 1.20.1 BusCheck compares the whole composite bus
                        // with Forge's own, then the scale listeners' sinks are compared bus vs dispatcher
                        List<String> fails = new java.util.ArrayList<>(BusCheck.check());
                        fails.addAll(BusAnatomy.sinkEquivalence(MinecraftForge.EVENT_BUS));
                        String eq = fails.isEmpty()
                                ? "equivalence: OK (" + BusCheck.expected() + "; per-listener sink counts match)\n"
                                : "equivalence: FAILED\n  " + String.join("\n  ", fails) + "\n";
                        String out = "# bus anatomy (in-game, server thread, best of 5)\n" + jvmLine() + "\n" + eq + "\n"
                                + BusAnatomy.format(BusAnatomy.run(400_000));
                        Path file = write(ctx.getSource().getServer(), "busbench", out);
                        say(ctx, out);
                        say(ctx, "sleep: written to " + file);
                    } catch (ReflectiveOperationException e) {
                        say(ctx, "sleep: busbench failed: " + e);
                    }
                    return 1;
                }))
                .then(Commands.literal("bench")
                        .executes(ctx -> bench(ctx, 2_000_000))
                        .then(Commands.argument("n", IntegerArgumentType.integer(10_000, 100_000_000))
                                .executes(ctx -> bench(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("events").executes(ctx -> {
                    // per-tick rates of every event mechanism since the previous call (vanilla + the bus)
                    String r = EventSources.report(ctx.getSource().getServer());
                    SleepMod.LOGGER.info("\n{}", r);
                    say(ctx, r);
                    return 1;
                }))
                .then(Commands.literal("world").executes(ctx -> {
                    say(ctx, worldStats(ctx.getSource().getServer()));
                    return 1;
                }))
                .then(Commands.literal("threads").executes(ctx -> {
                    // live threads by group + highest index seen per group + JVM-wide started count
                    var mx = ManagementFactory.getThreadMXBean();
                    java.util.TreeMap<String, int[]> g = new java.util.TreeMap<>();
                    for (Thread t : Thread.getAllStackTraces().keySet()) {
                        String n = t.getName();
                        var m = java.util.regex.Pattern.compile("^(.*?)[-# ]?(\\d+)$").matcher(n);
                        String key = m.matches() ? m.group(1) : n;
                        int idx = m.matches() ? Integer.parseInt(m.group(2)) : 0;
                        int[] v = g.computeIfAbsent(key, k -> new int[2]);
                        v[0]++;
                        v[1] = Math.max(v[1], idx);
                    }
                    StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                            "threads: live %d, peak %d, total started since JVM start %d%n", mx.getThreadCount(), mx.getPeakThreadCount(), mx.getTotalStartedThreadCount()));
                    g.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  %-44s live %3d   highest index %4d%n", k, v[0], v[1])));
                    SleepMod.LOGGER.info("\n{}", sb);
                    say(ctx, sb.toString());
                    return 1;
                }))
                .then(Commands.literal("buscheck").executes(ctx -> {
                    java.util.List<String> fails = BusCheck.check();
                    say(ctx, fails.isEmpty() ? "bus check: OK (" + BusCheck.expected() + "); " + dev.lull.optsupport.EventSites.report()
                            : "bus check: FAILED\n  " + String.join("\n  ", fails));
                    return fails.isEmpty() ? 1 : 0;
                }))
                .then(Commands.literal("audit").executes(ctx -> {
                    // every mixin applied now (Mixin loads each target): apply failures show at once, not when a class first loads
                    org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
                    say(ctx, "mixin audit done");
                    return 1;
                }))
                .then(Commands.literal("createbelt")   // scenes with the Create mod: its own belt placement between two shafts
                        .then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(ctx -> {
                                    var from = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "from");
                                    var to = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "to");
                                    try {
                                        Class.forName("com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem")
                                                .getMethod("createBelts", net.minecraft.world.level.Level.class, net.minecraft.core.BlockPos.class, net.minecraft.core.BlockPos.class)
                                                .invoke(null, ctx.getSource().getLevel(), from, to);
                                        return 1;
                                    } catch (ReflectiveOperationException e) {
                                        SleepMod.LOGGER.error("createbelt failed (is Create installed?)", e);
                                        return 0;
                                    }
                                }))))
                .then(Commands.literal("genbench")
                        .then(Commands.argument("side", IntegerArgumentType.integer(2, 64))
                                .then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                                    GenBench.start(ctx.getSource().getServer(), IntegerArgumentType.getInteger(ctx, "side"),
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "label"));
                                    say(ctx, "sleep: genbench started");
                                    return 1;
                                }))))
                .then(Commands.literal("settle").executes(ctx -> {
                    try {
                        say(ctx, TestSeeds.settleLight(ctx.getSource().getServer()));
                    } catch (ReflectiveOperationException | InterruptedException e) {
                        say(ctx, "light not settled: " + e);
                    }
                    return 1;
                }))
                .then(Commands.literal("placeall")   // test only: every block of a mod placed in a grid (testmods.ieform.IeForm.placeAll)
                        .then(Commands.argument("ns", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).then(Commands.argument("step", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String r = testmods.ieform.IeForm.placeAll(ctx.getSource().getLevel(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "ns"),
                                    new net.minecraft.core.BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")),
                                    IntegerArgumentType.getInteger(ctx, "step"));
                            System.out.println("[sleep] " + r);
                            say(ctx, r);
                            return 1;
                        })))))))
                .then(Commands.literal("useitem")   // test only: an item used the way a player places it, count times along (dx, dz)
                        .then(Commands.argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).then(Commands.argument("dx", IntegerArgumentType.integer())
                        .then(Commands.argument("dz", IntegerArgumentType.integer()).then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String r = testmods.ieform.IeForm.useItem(ctx.getSource().getLevel(), net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString(),
                                    new net.minecraft.core.BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")),
                                    IntegerArgumentType.getInteger(ctx, "dx"), IntegerArgumentType.getInteger(ctx, "dz"), IntegerArgumentType.getInteger(ctx, "count"));
                            System.out.println("[sleep] " + r);
                            say(ctx, r);
                            return 1;
                        })))))))))
                .then(Commands.literal("ieform")   // test only: every Immersive Engineering multiblock formed in a row (testmods.ieform)
                        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                            String r = testmods.ieform.IeForm.formAll(ctx.getSource().getLevel(), new net.minecraft.core.BlockPos(
                                    IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")));
                            System.out.println("[sleep] " + r);
                            say(ctx, r);
                            return 1;
                        })
                        .then(Commands.argument("only", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("copies", IntegerArgumentType.integer(1)).then(Commands.argument("step", IntegerArgumentType.integer(1)).executes(ctx -> {
                            String r = testmods.ieform.IeForm.formAll(ctx.getSource().getLevel(), new net.minecraft.core.BlockPos(
                                    IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")),
                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "only"), IntegerArgumentType.getInteger(ctx, "copies"),
                                    IntegerArgumentType.getInteger(ctx, "step"));
                            System.out.println("[sleep] " + r);
                            say(ctx, r);
                            return 1;
                        }))))))))
                .then(Commands.literal("reseed").executes(ctx -> {
                    // exactness tests: vanilla's clock-seeded randoms to fixed seeds, right before a scene is built
                    TestSeeds.reseed(ctx.getSource().getServer());
                    say(ctx, "randoms reseeded (identity hashes: core " + Integer.toHexString(System.identityHashCode(net.minecraft.world.entity.schedule.Activity.CORE))
                            + ", idle " + Integer.toHexString(System.identityHashCode(net.minecraft.world.entity.schedule.Activity.IDLE)) + ")");
                    return 1;
                }))
                .then(Commands.literal("bulktest")
                        .then(Commands.argument("entries", IntegerArgumentType.integer(1, 4096))
                        .then(Commands.argument("kb", IntegerArgumentType.integer(1, 8192))
                        .then(Commands.argument("changed", IntegerArgumentType.integer(0, 4096))
                        .then(Commands.argument("seed", IntegerArgumentType.integer()).executes(ctx -> {
                            String r = dev.lull.net.BulkSelfTest.publish(IntegerArgumentType.getInteger(ctx, "entries"), IntegerArgumentType.getInteger(ctx, "kb"),
                                    IntegerArgumentType.getInteger(ctx, "changed"), IntegerArgumentType.getInteger(ctx, "seed"));
                            SleepMod.LOGGER.info("sleep: bulktest published: {}", r);
                            say(ctx, "sleep: bulktest published: " + r);
                            for (var p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                                boolean served = dev.lull.net.Bulk.capable(p);
                                SleepMod.LOGGER.info("sleep: bulktest: {} {}", p.getScoreboardName(), served ? "is on the bulk path" : "is not on the bulk path");
                                if (!served) say(ctx, "sleep: bulktest: " + p.getScoreboardName() + " is not on the bulk path");
                            }
                            return 1;
                        }))))))
                .then(Commands.literal("net")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                .then(Commands.argument("label", StringArgumentType.word()).executes(ctx -> {
                                    NetBench.start(IntegerArgumentType.getInteger(ctx, "seconds"), StringArgumentType.getString(ctx, "label"));
                                    say(ctx, "sleep: net measurement started");
                                    return 1;
                                }))))
                .then(Commands.literal("hash")
                        .then(Commands.argument("x1", IntegerArgumentType.integer()).then(Commands.argument("y1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer()).then(Commands.argument("x2", IntegerArgumentType.integer())
                        .then(Commands.argument("y2", IntegerArgumentType.integer()).then(Commands.argument("z2", IntegerArgumentType.integer()).executes(ctx -> {
                            String r = regionHash(ctx.getSource().getLevel(), IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "y1"),
                                    IntegerArgumentType.getInteger(ctx, "z1"), IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "y2"),
                                    IntegerArgumentType.getInteger(ctx, "z2"));
                            System.out.println("[sleep] " + r);
                            say(ctx, r);
                            return 1;
                        })))))))));
        if (TICK) d.register(Commands.literal("tick").requires(s -> s.hasPermission(3))
                .then(Commands.literal("freeze").executes(ctx -> {
                    TickControl.freeze(true);
                    say(ctx, "The game is frozen");
                    return 1;
                }))
                .then(Commands.literal("unfreeze").executes(ctx -> {
                    TickControl.freeze(false);
                    say(ctx, "The game is running normally");
                    return 1;
                }))
                .then(Commands.literal("step").then(Commands.argument("time", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "time");
                    TickControl.step(n);
                    say(ctx, "Stepping " + n + " tick(s)");
                    return 1;
                })))
                .then(Commands.literal("sprint").then(Commands.argument("time", IntegerArgumentType.integer(1)).executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "time");
                    TickControl.sprint(n);
                    say(ctx, "Sprinting " + n + " tick(s)");
                    return 1;
                })))
                .then(Commands.literal("query").executes(ctx -> {
                    for (String line : TickControl.query(ctx.getSource().getServer())) say(ctx, line);
                    return 1;
                })));
    }

    /** The end of every server tick (TickTimer, LOWEST): the benches' clocks and the auto-stop of /sleep run. */
    static void onTickEnd(net.minecraft.server.MinecraftServer server) {
        IdleBench.onTickEnd(server);
        GenBench.onTickEnd(server);
        NetBench.onTickEnd(server);
        if (autoStopAtTick >= 0 && TickTimer.INSTANCE.ticks() >= autoStopAtTick) {
            autoStopAtTick = -1;
            Path file = finish(server, autoLabel);
            server.getPlayerList().broadcastSystemMessage(Component.literal("sleep: run '" + autoLabel + "' done -> " + file), false);
            SleepMod.LOGGER.info("sleep: run '{}' done -> {}\n{}", autoLabel, file, summary());
        }
    }

    // ------------------------------------------------------------------ recordings (1.21.1's, on 1.20.1)

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static void startRecording() {
        TickTimer.INSTANCE.reset();
        TaskStats.reset();
        JvmStats.start();
        EventCounter.INSTANCE.start();
    }

    /** Stops recording and writes report-<label>; {@code headline} goes on top of the report. */
    static Path finishRecording(MinecraftServer server, String label, String headline) {
        EventCounter.INSTANCE.stop();
        Path file = write(server, "report-" + label, headline + "\n" + fullReport(server, label));
        appendCsv(server, label);
        return file;
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        startRecording();
        say(ctx, EventCounter.INSTANCE.available()
                ? "sleep: recording. Tick stats reset, counting " + EventCounter.INSTANCE.trackedTypes() + " event types (more are discovered every second)."
                : "sleep: recording tick stats only (EventBus internals not reachable, see log).");
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        autoStopAtTick = -1;
        Path file = finish(ctx.getSource().getServer(), "manual");
        say(ctx, "sleep: stopped, report written to " + file);
        say(ctx, summary());
        return 1;
    }

    /** Pending automatic stop for /sleep run (server thread only). */
    private static int autoStopAtTick = -1;
    private static String autoLabel = "";

    /** 1.20.1 has no /tick sprint (1.21.1 runs a sprint for its seconds of wall time): a run is seconds * 20 ticks */
    private static int run(CommandContext<CommandSourceStack> ctx, int seconds, String label) {
        start(ctx);
        autoStopAtTick = seconds * 20;
        autoLabel = label;
        say(ctx, "sleep: run '" + label + "' for " + seconds + " s (" + autoStopAtTick + " ticks)");
        return 1;
    }

    private static Path finish(MinecraftServer server, String label) {
        EventCounter.INSTANCE.stop();
        Path file = write(server, "report-" + label, fullReport(server, label));
        appendCsv(server, label);
        return file;
    }

    private static void appendCsv(MinecraftServer server, String label) {
        TickTimer t = TickTimer.INSTANCE;
        TickTimer.Stats d = t.durationStats();
        TickTimer.Stats a = t.allocStats();
        TickTimer.Stats iv = t.intervalStats();
        long ticks = Math.max(1, t.ticks());
        long events = 0;
        for (EventCounter.Counter c : EventCounter.INSTANCE.snapshotSorted()) {
            events += c.serverThread.sum();
        }
        int entities = 0, tickers = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity ignored : level.getAllEntities()) {
                entities++;
            }
            String bt = blockEntityTickers(level);
            tickers += bt.equals("?") ? 0 : Integer.parseInt(bt);
        }
        Path csv = outDir(server).resolve("summary.csv");
        String header = "time,label,ticks,mspt_mean,mspt_p50,mspt_p99,mspt_max,interval_p99,alloc_kib_mean,alloc_kib_p99,events_per_tick,entities,ticking_block_entities,loop_tells_per_tick,mailbox_tasks_per_tick,window_s,"
                + String.join(",", JvmStats.csvHeader()) + "\n";
        String row = String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%d,%d,%.1f,%.1f,%.1f,%s%n",
                LocalDateTime.now().withNano(0), label, t.ticks(), d.mean() / 1e6, d.p50() / 1e6, d.p99() / 1e6,
                d.max() / 1e6, iv.p99() / 1e6, a.mean() / 1024, a.p99() / 1024.0, (double) events / ticks, entities, tickers,
                (double) TaskStats.totalLoopTells() / ticks, (double) TaskStats.totalMailboxTasks() / ticks,
                t.windowSeconds(), JvmStats.csvRow());
        try {
            Files.createDirectories(csv.getParent());
            if (Files.exists(csv) && !Files.readAllLines(csv).get(0).equals(header.strip())) {
                // schema changed: keep the old file, start a new one
                Files.move(csv, csv.resolveSibling("summary-" + LocalDateTime.now().format(FILE_TS) + "-oldschema.csv"));
            }
            if (!Files.exists(csv)) {
                Files.writeString(csv, header);
            }
            Files.writeString(csv, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot append {}", csv, e);
        }
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        say(ctx, summary());
        return 1;
    }

    private static int bench(CommandContext<CommandSourceStack> ctx, int n) {
        if (EventCounter.INSTANCE.enabled()) {
            say(ctx, "sleep: run /sleep stop first. Counting listeners would distort the bench.");
            return 0;
        }
        say(ctx, "sleep: benchmarking IEventBus.post, n=" + n + " (the server thread is blocked while it runs)...");
        long t0 = System.nanoTime();
        List<EventBench.Result> results = EventBench.run(n);
        StringBuilder sb = new StringBuilder();
        sb.append("# IEventBus.post microbenchmark (in-game, server thread)\n")
                .append("n per run = ").append(n).append(", best of 5 after warm-up, took ")
                .append(String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - t0) / 1e9)).append('\n')
                .append(jvmLine()).append("\n\n");
        for (EventBench.Result r : results) {
            sb.append(r).append('\n');
        }
        Path file = write(ctx.getSource().getServer(), "bench", sb.toString());
        for (EventBench.Result r : results) {
            say(ctx, r.toString());
        }
        say(ctx, "sleep: written to " + file);
        return 1;
    }

    private static String summary() {
        TickTimer t = TickTimer.INSTANCE;
        TickTimer.Stats d = t.durationStats();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "ticks=%d  MSPT p50=%.2f p99=%.2f max=%.2f ms  alloc p50=%.0f KiB/tick",
                t.ticks(), d.p50() / 1e6, d.p99() / 1e6, d.max() / 1e6, t.allocStats().p50() / 1024.0));
        List<EventCounter.Counter> top = EventCounter.INSTANCE.snapshotSorted();
        double secs = EventCounter.INSTANCE.windowSeconds();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            EventCounter.Counter c = top.get(i);
            sb.append(String.format(Locale.ROOT, "%n  %,10.0f/s  %s", c.total() / secs, shortName(c.type)));
        }
        return sb.toString();
    }

    private static String fullReport(MinecraftServer server, String label) {
        TickTimer t = TickTimer.INSTANCE;
        StringBuilder sb = new StringBuilder();
        sb.append("# sleep report '").append(label).append("' ").append(LocalDateTime.now()).append('\n')
                .append(jvmLine()).append('\n')
                .append(String.format(Locale.ROOT, "window: %.1f s, %d ticks recorded%n%n", t.windowSeconds(), t.ticks()));

        sb.append("## Server tick (ServerTickEvent START -> END)\n")
                .append("duration  ").append(t.durationStats().asMillis()).append('\n')
                .append("interval  ").append(t.intervalStats().asMillis()).append("   (target 50 ms; spread = scheduling jitter)\n")
                .append("alloc     ").append(ThreadAlloc.supported() ? t.allocStats().asKiB() : "unsupported").append("   (server thread only)\n\n");

        sb.append("## Per level (LevelTickEvent START -> END)\n");
        for (Map.Entry<String, TickTimer.LevelStats> e : t.levels().entrySet()) {
            TickTimer.LevelStats s = e.getValue();
            sb.append(String.format(Locale.ROOT, "%-28s mean=%.3f ms max=%.3f ms ticks=%d%n",
                    e.getKey(), s.ticks == 0 ? 0 : s.sum / 1e6 / s.ticks, s.max / 1e6, s.ticks));
        }

        sb.append('\n').append(worldStats(server)).append('\n');
        sb.append(TaskStats.report(Math.max(1e-9, t.windowSeconds()), Math.max(1, t.ticks()))).append('\n');
        sb.append(JvmStats.report()).append('\n');

        List<EventCounter.Counter> counters = EventCounter.INSTANCE.snapshotSorted();
        double secs = EventCounter.INSTANCE.windowSeconds();
        long ticks = Math.max(1, t.ticks());
        sb.append("\n## Events posted on MinecraftForge.EVENT_BUS (window ").append(String.format(Locale.ROOT, "%.1f s", secs)).append(")\n")
                .append(String.format(Locale.ROOT, "%14s %12s %12s %12s %10s  %s%n", "total", "per sec", "per tick", "off-server", "listeners", "event"));
        for (EventCounter.Counter c : counters) {
            long total = c.total();
            sb.append(String.format(Locale.ROOT, "%,14d %,12.0f %,12.1f %,12d %10s  %s%n",
                    total, total / secs, (double) c.serverThread.sum() / ticks, c.otherThreads.sum(),
                    c.listeners < 0 ? "?" : Integer.toString(c.listeners), c.type.getName()));
        }
        sb.append("\n'off-server' = posted from any thread other than the server thread (render thread, workers, netty).\n")
                .append("'listeners' = listeners one post invokes (incl. parent types), excluding the engine's own counter.\n")
                .append("Not counted on 1.20.1: generic events (").append(EventCounter.INSTANCE.rejectedTypes())
                .append(" types refused, e.g. AttachCapabilitiesEvent: Forge takes their listeners only per generic type).\n");
        return sb.toString();
    }

    static String worldStats(MinecraftServer server) {
        StringBuilder sb = new StringBuilder("## World\n");
        for (ServerLevel level : server.getAllLevels()) {
            int entities = 0;
            for (Entity ignored : level.getAllEntities()) {
                entities++;
            }
            sb.append(String.format(Locale.ROOT, "%-28s entities=%d tickingBlockEntities=%s loadedChunks=%d%n",
                    level.dimension().location(), entities, blockEntityTickers(level),
                    level.getChunkSource().getLoadedChunksCount()));
        }
        return sb.toString();
    }

    private static Field tickersField;

    /** Level.blockEntityTickers is protected; 1.20.1 production runs on SRG names (Names) */
    private static String blockEntityTickers(Level level) {
        try {
            if (tickersField == null) {
                tickersField = Level.class.getDeclaredField(Names.field("net/minecraft/world/level/Level", "blockEntityTickers"));
                tickersField.setAccessible(true);
            }
            return String.valueOf(((List<?>) tickersField.get(level)).size());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    private static String jvmLine() {
        var rt = ManagementFactory.getRuntimeMXBean();
        return String.format(Locale.ROOT, "JVM %s %s, %d CPUs, max heap %d MiB, args %s",
                rt.getVmVendor(), System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() >> 20, rt.getInputArguments().stream()
                        .filter(a -> a.startsWith("-X") || a.startsWith("-XX")).toList());
    }

    private static String shortName(Class<?> type) {
        String n = type.getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    /** the game directory's measurement folder (1.20.1's getServerDirectory is a File) */
    private static Path outDir(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve(dev.lull.SleepOut.ROOT);
    }

    private static Path write(MinecraftServer server, String kind, String content) {
        Path dir = outDir(server);
        Path file = dir.resolve(kind + "-" + LocalDateTime.now().format(FILE_TS) + ".txt");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, content);
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write {}", file, e);
        }
        SleepMod.LOGGER.info("\n{}", content);
        return file.toAbsolutePath();
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
    }

    static String canonNbt(net.minecraft.nbt.Tag t) {
        if (t instanceof net.minecraft.nbt.CompoundTag c) {
            StringBuilder sb = new StringBuilder("{");
            for (String k : new java.util.TreeSet<>(c.getAllKeys())) sb.append(k).append(':').append(canonNbt(c.get(k))).append(',');
            return sb.append('}').toString();
        }
        if (t instanceof net.minecraft.nbt.ListTag l && !l.isEmpty() && l.get(0) instanceof net.minecraft.nbt.CompoundTag) {
            java.util.List<String> els = new java.util.ArrayList<>();
            for (net.minecraft.nbt.Tag x : l) els.add(canonNbt(x));
            java.util.Collections.sort(els);
            return els.toString();
        }
        if (t instanceof net.minecraft.nbt.ListTag l) {
            StringBuilder sb = new StringBuilder("[");
            for (net.minecraft.nbt.Tag x : l) sb.append(canonNbt(x)).append(',');
            return sb.append(']').toString();
        }
        return String.valueOf(t);
    }

    private static final java.util.List<String> BE_ROWS = System.getProperty("sleep.hashBeRows") != null ? new java.util.ArrayList<>() : null;

    /** The region's blocks, block entities (saved and client NBT) and, with -Dsleep.hashEntities, its entities. */
    static String regionHash(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2) {
        long h = 1125899906842597L;
        int bes = 0;
        var p = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    p.set(x, y, z);
                    h = h * 31 + level.getBlockState(p).toString().hashCode();
                    var be = level.getBlockEntity(p);
                    if (be != null) {
                        bes++;
                        // a mod's block entity that throws while saving is a row of its own, not the end of the hash
                        String nbt;
                        try {
                            nbt = be.saveWithoutMetadata().toString();
                        } catch (RuntimeException e) {
                            nbt = "error: " + e;
                        }
                        h = h * 31 + nbt.hashCode();
                        String client = null;
                        if (Boolean.getBoolean("sleep.hashClientNbt")) {
                            try {
                                client = String.valueOf(be.getUpdateTag());
                            } catch (RuntimeException e) {
                                client = "error: " + e;
                            }
                        }
                        if (client != null) h = h * 31 + client.hashCode();
                        if (BE_ROWS != null) BE_ROWS.add(p.toShortString() + " " + level.getBlockState(p) + " " + nbt + (client != null ? " client=" + client : ""));
                    }
                }
        if (BE_ROWS != null) {
            try {
                java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("sleep.hashBeRows")), BE_ROWS);
            } catch (java.io.IOException e) {
                System.out.println("[sleep/hash] block entity rows not written: " + e);
            }
            BE_ROWS.clear();
        }
        int ents = 0;
        if (Boolean.getBoolean("sleep.hashEntities")) {
            var box = new net.minecraft.world.phys.AABB(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, box, x -> !(x instanceof net.minecraft.world.entity.player.Player))) {
                var tag = new net.minecraft.nbt.CompoundTag();
                e.saveWithoutId(tag);
                tag.remove("UUID");
                // 1.20.1: attribute modifiers made at spawn ("Random spawn bonus"...) take Mth.createInsecureUUID() - Mth's own
                // unseeded random, per run (1.21.1 gives them fixed ids); amounts, names and operations stay compared
                for (net.minecraft.nbt.Tag at : tag.getList("Attributes", net.minecraft.nbt.Tag.TAG_COMPOUND))
                    for (net.minecraft.nbt.Tag m : ((net.minecraft.nbt.CompoundTag) at).getList("Modifiers", net.minecraft.nbt.Tag.TAG_COMPOUND))
                        ((net.minecraft.nbt.CompoundTag) m).remove("UUID");
                rows.add(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " " + Double.doubleToLongBits(e.getX()) + " "
                        + Double.doubleToLongBits(e.getY()) + " " + Double.doubleToLongBits(e.getZ()) + " " + e.getDeltaMovement() + " " + canonNbt(tag)
                        + (Boolean.getBoolean("sleep.hashIds") ? " id=" + e.getId() : "")   // debugging a scene's determinism
                        + (Boolean.getBoolean("sleep.hashEntitiesDeep") ? " deep=" + Long.toHexString(DeepHash.of(e)) + DeepHash.lastFields() : ""));
            }
            java.util.Collections.sort(rows);
            String rowsFile = System.getProperty("sleep.hashRows");
            if (rowsFile != null) {
                try {
                    java.nio.file.Files.write(java.nio.file.Path.of(rowsFile), rows);
                } catch (java.io.IOException ex) {
                    System.out.println("[sleep/hash] rows not written: " + ex);
                }
            }
            for (String r : rows) h = h * 31 + r.hashCode();
            ents = rows.size();
        }
        return String.format(Locale.ROOT, "region hash %016x (block entities %d, entities %d, game time %d)", h, bes, ents, level.getGameTime());
    }
}
