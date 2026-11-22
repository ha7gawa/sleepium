package dev.lull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk pipeline benchmark: force-load an N x N square of never-generated chunks far from spawn and measure
 * how long until every one is a FULL LevelChunk. Exercises the worldgen / light / sorter mailboxes, IO and the
 * chunk-source main executor — the cross-thread paths that stay idle in the static lag scenes.
 *
 * <p>{@code /sleep genbench <side> <label>}: side x side chunks (e.g. 24 -> 576 chunks). Written: a report
 * (genbench-<label>.txt: speed, CPU, generation phases), a row in genbench.csv, the per-chunk hashes and, with
 * "steps" in -Dsleep.genbench.hash, every step's input / output hash (genstep-<label>.txt, tools/genstep.py compares
 * two runs). 1.20.1: no /sleep recording of tick and event counters yet (the 1.21.1 report had them).
 */
final class GenBench {
    private static ServerLevel level;
    private static int x0, z0, side, total;
    private static long startNanos;
    private static int startTick;
    private static String label = "";
    private static int runIndex;
    private static boolean active;
    private static CpuWindow cpu;
    /** Hash of each chunk taken at ChunkEvent.Load (end of the FULL step), before post-processing or any tick. */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap loadHashes = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /**
     * -Dsleep.genbench.hash: "carvers,full" (default), "carvers", "full", "steps" (with the others or alone) or "none".
     * Hashing reads all ~98k block states of a chunk: the FULL hash runs on the server thread (a quarter of its time at
     * 140 chunks/s, inflating MSPT), the CARVERS hash on the worker that finished the step; "steps" hashes every step's
     * window twice (GenBenchHooks). Throughput runs use "none".
     */
    static final String HASH = System.getProperty("sleep.genbench.hash", "carvers,full");
    static final boolean HASH_FULL = HASH.contains("full"), HASH_CARVERS = HASH.contains("carvers"), HASH_STEPS = HASH.contains("steps");

    /** chunks of this level that reached FULL (ChunkEvent.Load) during the current tick, any position */
    private static int loadsThisTick;
    /** per tick of the run: FULL loads during it, and its duration (ns) */
    private static final it.unimi.dsi.fastutil.ints.IntArrayList TICK_LOADS = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final it.unimi.dsi.fastutil.longs.LongArrayList TICK_NANOS = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static long lastTickEnd;
    /** when the area's last chunk became FULL (s since start, ticks); -1 before */
    private static double doneSecs = -1;
    private static int doneTicks;
    private static String probeLine = "";

    static void onChunkLoad(net.minecraftforge.event.level.ChunkEvent.Load e) {
        if (active && e.getLevel() == level) loadsThisTick++;
        if (!active || !HASH_FULL || e.getLevel() != level) return;
        ChunkPos p = e.getChunk().getPos();
        if (p.x < x0 || p.x >= x0 + side || p.z < z0 || p.z >= z0 + side) return;
        loadHashes.put(p.toLong(), hashChunk(e.getChunk()));
    }

    static long hashChunk(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        long h = 1469598103934665603L;   // FNV-1a over block state ids, fixed order
        for (var section : chunk.getSections()) {
            if (section.hasOnlyAir()) {
                h = (h ^ 0x9E37) * 1099511628211L;
                continue;
            }
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int id = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(section.getBlockState(x, y, z));
                        h = (h ^ id) * 1099511628211L;
                    }
        }
        // the biome of every quart too (-Dsleep.genbench.biomes=false: blocks only, as before 11-09): a biome that changes
        // no block would not show in the block states
        if (!"false".equals(System.getProperty("sleep.genbench.biomes")))
            for (var section : chunk.getSections())
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++)
                        for (int x = 0; x < 4; x++) {
                            var b = section.getNoiseBiome(x, y, z);
                            int id = b.unwrapKey().map(k -> k.location().hashCode()).orElse(0);
                            h = (h ^ id) * 1099511628211L;
                        }
        return h;
    }

    private GenBench() {}

    /** the game directory's measurement folder (1.20.1's getServerDirectory is a File) */
    private static Path out(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve(dev.lull.SleepOut.ROOT);
    }

    /**
     * Every recorded step of the area's chunks as "cx cz status input output" (genstep-<label>.txt), statuses in
     * generation order; a step that never ran here (a chunk loaded instead of generated) is absent.
     */
    private static void writeStepHashes(MinecraftServer server) {
        StringBuilder b = new StringBuilder();
        for (net.minecraft.world.level.chunk.ChunkStatus st : net.minecraft.world.level.chunk.ChunkStatus.getStatusList())
            for (int dx = 0; dx < side; dx++)
                for (int dz = 0; dz < side; dz++) {
                    long[] io = GenBenchHooks.STEPS.get(GenBenchHooks.stepKey(st.getIndex(), new ChunkPos(x0 + dx, z0 + dz)));
                    if (io == null) continue;
                    b.append(x0 + dx).append(' ').append(z0 + dz).append(' ').append(st).append(' ')
                            .append(Long.toHexString(io[0])).append(' ').append(Long.toHexString(io[1])).append('\n');
                }
        Path f = out(server).resolve("genstep-" + label + ".txt");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, b.toString());
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write {}", f, e);
        }
        GenBenchHooks.STEPS.clear();
    }

    static boolean active() {
        return active;
    }

    /**
     * -Dsleep.genbench.ordered=true (exactness runs, tools/genstep.py): FEATURES in one fixed order. On 1.20.1 the order
     * neighbours run FEATURES in decides each 3x3's input, so with the vanilla schedule most FEATURES steps cannot be
     * compared between two runs. Here the area and the 3 rings its forced chunks generate to FEATURES (ticket level 31:
     * rings 0..2 FULL, ring 3 INITIALIZE_LIGHT) are first taken to FEATURES one chunk at a time, row by row: a ticket of
     * level 34 (byStatus(FEATURES): the chunk itself to INITIALIZE_LIGHT, its ring to CARVERS) runs that chunk's
     * FEATURES and no other, and the next chunk starts only when it is done. Then the area is forced as usual (no
     * FEATURES left to run). The time measured is then meaningless; every step is comparable.
     */
    static final boolean ORDERED = Boolean.getBoolean("sleep.genbench.ordered");
    private static final net.minecraft.server.level.TicketType<ChunkPos> ORDER_TICKET =
            net.minecraft.server.level.TicketType.create("sleep_genbench", java.util.Comparator.comparingLong(ChunkPos::toLong));
    private static final int ORDER_MARGIN = 3;
    private static final java.util.ArrayList<ChunkPos> ORDER = new java.util.ArrayList<>();
    /** the next chunk of ORDER to take to FEATURES; -1 = no ordered pass running */
    private static int orderNext = -1;

    /** server thread, end of a tick: the ordered pass, chunk by chunk (each getChunk waits for its FEATURES) */
    private static boolean orderedPass() {
        if (orderNext < 0) return false;
        long until = System.nanoTime() + 40_000_000L;
        while (orderNext < ORDER.size() && System.nanoTime() < until) {
            ChunkPos p = ORDER.get(orderNext++);
            level.getChunkSource().addRegionTicket(ORDER_TICKET, p, -1, p);   // level 33 - (-1) = 34
            level.getChunkSource().getChunk(p.x, p.z, net.minecraft.world.level.chunk.ChunkStatus.FEATURES, true);
        }
        if (orderNext < ORDER.size()) return true;
        orderNext = -1;
        SleepMod.LOGGER.info("sleep: genbench '{}' ordered FEATURES pass done ({} chunks), forcing the area", label, ORDER.size());
        force(true);
        return true;
    }

    private static void force(boolean on) {
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                // Async FORCED ticket (ticket level 31). NOT level.setChunkForced(): that also calls getChunk()
                // synchronously, i.e. generates one chunk at a time on the server thread (the /forceload command
                // does the same and freezes the server: 256 chunks = 7.6 s in one tick).
                level.getChunkSource().updateChunkForced(new ChunkPos(x0 + dx, z0 + dz), on);
            }
        }
    }

    static void start(MinecraftServer server, int sideChunks, String l) {
        // -Dsleep.genbench.dim=minecraft:the_nether | minecraft:the_end (default the overworld)
        String dim = System.getProperty("sleep.genbench.dim");
        level = dim == null ? server.overworld()
                : server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        new net.minecraft.resources.ResourceLocation(dim)));
        if (level == null) level = server.overworld();
        side = sideChunks;
        total = side * side;
        label = l;
        // A fresh region every run, far from spawn and from previous runs (1 run = 1 column of regions).
        x0 = 4000 + (runIndex++) * (side + 8);
        z0 = 4000;
        loadHashes.clear();
        GenBenchHooks.CARVERS.clear();
        GenBenchHooks.STEPS.clear();
        GenBenchHooks.x0 = x0;
        GenBenchHooks.z0 = z0;
        GenBenchHooks.side = side;
        GenBenchHooks.active = true;
        startTick = TickTimer.INSTANCE.ticks();
        startNanos = System.nanoTime();
        cpu = new CpuWindow();
        if (LightProbe.ON) LightProbe.reset();
        doneSecs = -1;
        if (ORDERED) {
            ORDER.clear();
            for (int z = z0 - ORDER_MARGIN; z < z0 + side + ORDER_MARGIN; z++)
                for (int x = x0 - ORDER_MARGIN; x < x0 + side + ORDER_MARGIN; x++) ORDER.add(new ChunkPos(x, z));
            orderNext = 0;
        } else {
            force(true);
        }
        active = true;
        SleepMod.LOGGER.info("sleep: genbench '{}' started, {} chunks at chunk {},{}", label, total, x0, z0);
    }

    /**
     * Content hash of every generated chunk (all block states of all sections, in a fixed order) to
     * run/probe/genhash-<label>.txt as "cx cz hash". Used to check that parallel generation produces the same world.
     */
    private static String writeChunkHashes(MinecraftServer server) {
        StringBuilder out = new StringBuilder();
        long all = 1469598103934665603L;
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                long h = loadHashes.getOrDefault(ChunkPos.asLong(x0 + dx, z0 + dz), 0L);   // 0 = no Load seen
                out.append(x0 + dx).append(' ').append(z0 + dz).append(' ').append(Long.toHexString(h)).append('\n');
                all = (all ^ h) * 1099511628211L;
            }
        }
        StringBuilder carv = new StringBuilder();
        for (int dx = 0; dx < side; dx++)
            for (int dz = 0; dz < side; dz++) {
                Long c = GenBenchHooks.CARVERS.get(ChunkPos.asLong(x0 + dx, z0 + dz));
                carv.append(x0 + dx).append(' ').append(z0 + dz).append(' ').append(c == null ? "0" : Long.toHexString(c)).append('\n');
            }
        try {
            Files.createDirectories(out(server));
            Files.writeString(out(server).resolve("genhash-" + label + "-carvers.txt"), carv.toString());
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write carvers hashes", e);
        }
        if (HASH_STEPS) writeStepHashes(server);
        Path f = out(server).resolve("genhash-" + label + ".txt");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, out.toString());
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write {}", f, e);
        }
        String par = dev.lull.optsupport.ParallelGen.ENABLED ? "; " + dev.lull.optsupport.ParallelGen.report() : "";
        if (dev.lull.optsupport.GenReactor.ENABLED) par += "; " + dev.lull.optsupport.GenReactor.report();
        if (dev.lull.optsupport.FullRing.ENABLED) par += "; " + dev.lull.optsupport.FullRing.report();
        if (dev.lull.optsupport.LightNotifyRing.ENABLED) par += "; " + dev.lull.optsupport.LightNotifyRing.report();
        if (dev.lull.optsupport.GenReactor.ENABLED) par += "; " + dev.lull.optsupport.ReadJournal.report();
        if (dev.lull.optsupport.MainReactor.ENABLED) par += "; " + dev.lull.optsupport.MainReactor.wakeReport();
        if (Boolean.getBoolean("lab.reactor.scout")) par += "; " + dev.lull.optsupport.MainReactor.scoutReport();
        return "world hash " + Long.toHexString(all) + par + " /" + " (" + total + " chunks, per-chunk hashes in " + f.getFileName() + ")";
    }

    /**
     * FULL loads per tick (includes chunks generated in the margin), and whether ticks after a burst are long: the
     * loads of tick i against the end-to-end duration of tick i + 1 (its new chunks' first ticks).
     */
    private static String burstReport() {
        int n = TICK_LOADS.size();
        if (n < 3) return "loads per tick: n/a";
        int[] l = TICK_LOADS.toIntArray();
        int[] sorted = l.clone();
        java.util.Arrays.sort(sorted);
        long heavy = 0, light = 0;
        int nh = 0, nl = 0;
        for (int i = 1; i + 1 < n; i++) {
            long d = TICK_NANOS.getLong(i + 1);
            if (l[i] >= 16) { heavy += d; nh++; } else { light += d; nl++; }
        }
        TICK_LOADS.clear();
        TICK_NANOS.clear();
        lastTickEnd = 0;
        return String.format(Locale.ROOT, "loads per tick: p50 %d, p90 %d, max %d; next tick after >= 16 loads %.1f ms (%d ticks), after < 16 %.1f ms (%d ticks)",
                sorted[n / 2], sorted[(int) (n * 0.9)], sorted[n - 1], nh == 0 ? 0 : heavy / 1e6 / nh, nh, nl == 0 ? 0 : light / 1e6 / nl, nl);
    }

    /** Server thread, end of every tick. */
    static void onTickEnd(MinecraftServer server) {
        if (!active) {
            return;
        }
        if (orderedPass()) return;
        long now = System.nanoTime();
        TICK_LOADS.add(loadsThisTick);
        TICK_NANOS.add(lastTickEnd == 0 ? 0 : now - lastTickEnd);
        loadsThisTick = 0;
        lastTickEnd = now;
        int done = 0;
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                if (level.getChunkSource().getChunkNow(x0 + dx, z0 + dz) != null) {
                    done++;
                }
            }
        }
        double secs = (System.nanoTime() - startNanos) / 1e9;
        if (GenTimeline.ON && doneSecs < 0) GenTimeline.sample(level, x0, z0, side, secs);
        if (done < total && secs < 600) {
            return;
        }
        if (doneSecs < 0) {   // the area's time and ticks: when its last chunk became FULL (the light check below may wait)
            doneSecs = secs;
            doneTicks = TickTimer.INSTANCE.ticks() - startTick;
            probeLine = LightProbe.ON ? System.lineSeparator() + LightProbe.report(done) : "";
        }
        String lightLine = probeLine;
        if (LightCheck.ON) {
            if (!LightCheck.ready(level)) return;   // until the light actor is idle, then drained with the server thread held (LightCheck.drain)
            lightLine += System.lineSeparator() + LightCheck.run(level, x0, z0, side,
                    server.getServerDirectory().toPath().resolve(dev.lull.SleepOut.ROOT).resolve("genlight-" + label + ".txt"));
        }
        secs = doneSecs;
        active = false;
        GenBenchHooks.active = false;
        int ticks = doneTicks;
        String line = String.format(Locale.ROOT, "genbench '%s': %d/%d chunks FULL in %.2f s (%d ticks) = %.1f chunks/s",
                label, done, total, secs, ticks, done / secs);
        String cpuLine = burstReport() + System.lineSeparator() + cpu.report(secs, 12) + System.lineSeparator() + GenProfile.reportAndReset(done)
                + (Boolean.getBoolean("lab.genscout") ? System.lineSeparator() + dev.lull.optsupport.GenScout.reportAndReset() + System.lineSeparator() + dev.lull.optsupport.GenScout.perlinBench()
                        + System.lineSeparator() + dev.lull.optsupport.ColumnBound.report() : "");
        if (GenTimeline.ON) cpuLine += System.lineSeparator() + GenTimeline.reportAndReset();
        cpuLine += lightLine;
        String hashLine = writeChunkHashes(server) + "\n" + cpuLine;
        Path report = out(server).resolve("genbench-" + label + ".txt");
        try {
            Files.writeString(report, line + "\n" + hashLine + "\n");
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write {}", report, e);
        }
        force(false);
        for (ChunkPos p : ORDER) level.getChunkSource().removeRegionTicket(ORDER_TICKET, p, -1, p);
        ORDER.clear();
        Path csv = out(server).resolve("genbench.csv");
        try {
            if (!Files.exists(csv)) {
                Files.writeString(csv, "time,label,chunks,seconds,chunks_per_s,ticks\n");
            }
            Files.writeString(csv, String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.2f,%d%n",
                    LocalDateTime.now().withNano(0), label, done, secs, done / secs, ticks), StandardOpenOption.APPEND);
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot append {}", csv, e);
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal("sleep: " + line), false);
        SleepMod.LOGGER.info("sleep: {} -> {}", line, report);
        SleepMod.LOGGER.info("sleep: genbench '{}' {}", label, cpuLine);
    }
}
