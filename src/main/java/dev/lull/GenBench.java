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
 * <p>{@code /sleep genbench <side> <label>}: side x side chunks (e.g. 24 -> 576 chunks). Tick stats, event and task
 * counters run for the whole generation and are written as a normal report plus a row in genbench.csv.
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
     * -Dsleep.genbench.hash: "carvers,full" (default), "carvers", "full" or "none". Hashing reads all ~98k block states
     * of a chunk: the FULL hash runs on the server thread (a quarter of its time at 140 chunks/s, inflating MSPT), the
     * CARVERS hash on the worker that finished the step. Throughput runs use "none".
     */
    static final String HASH = System.getProperty("sleep.genbench.hash", "carvers,full");
    static final boolean HASH_FULL = HASH.contains("full"), HASH_CARVERS = HASH.contains("carvers");

    /** chunks of this level that reached FULL (ChunkEvent.Load) during the current tick, any position */
    private static int loadsThisTick;
    /** per tick of the run: FULL loads during it, and its duration (ns) */
    private static final it.unimi.dsi.fastutil.ints.IntArrayList TICK_LOADS = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final it.unimi.dsi.fastutil.longs.LongArrayList TICK_NANOS = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static long lastTickEnd;

    static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load e) {
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

    static boolean active() {
        return active;
    }

    static void start(MinecraftServer server, int sideChunks, String l) {
        // -Dsleep.genbench.dim=minecraft:the_nether | minecraft:the_end (default the overworld)
        String dim = System.getProperty("sleep.genbench.dim");
        level = dim == null ? server.overworld()
                : server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(dim)));
        if (level == null) level = server.overworld();
        side = sideChunks;
        total = side * side;
        label = l;
        // A fresh region every run, far from spawn and from previous runs (1 run = 1 column of regions).
        x0 = 4000 + (runIndex++) * (side + 8);
        z0 = 4000;
        loadHashes.clear();
        GenBenchHooks.CARVERS.clear();
        GenBenchHooks.x0 = x0;
        GenBenchHooks.z0 = z0;
        GenBenchHooks.side = side;
        GenBenchHooks.active = true;
        SleepCommand.startRecording();
        startTick = TickTimer.INSTANCE.ticks();
        startNanos = System.nanoTime();
        cpu = new CpuWindow();
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                // Async FORCED ticket (ticket level 31). NOT level.setChunkForced(): that also calls getChunk()
                // synchronously, i.e. generates one chunk at a time on the server thread (the /forceload command
                // does the same and freezes the server: 256 chunks = 7.6 s in one tick).
                level.getChunkSource().updateChunkForced(new ChunkPos(x0 + dx, z0 + dz), true);
            }
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
            Files.createDirectories(server.getServerDirectory().resolve(dev.lull.SleepOut.ROOT));   // a fresh run directory has none yet
            Files.writeString(server.getServerDirectory().resolve(dev.lull.SleepOut.ROOT).resolve("genhash-" + label + "-carvers.txt"), carv.toString());
        } catch (IOException e) {
            SleepMod.LOGGER.error("Cannot write carvers hashes", e);
        }
        Path f = server.getServerDirectory().resolve(dev.lull.SleepOut.ROOT).resolve("genhash-" + label + ".txt");
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
        if (done < total && secs < 600) {
            return;
        }
        active = false;
        GenBenchHooks.active = false;
        int ticks = TickTimer.INSTANCE.ticks() - startTick;
        String line = String.format(Locale.ROOT, "genbench '%s': %d/%d chunks FULL in %.2f s (%d ticks) = %.1f chunks/s",
                label, done, total, secs, ticks, done / secs);
        String cpuLine = burstReport() + System.lineSeparator() + cpu.report(secs, 12) + System.lineSeparator() + GenProfile.reportAndReset(done)
                + (Boolean.getBoolean("lab.genscout") ? System.lineSeparator() + dev.lull.optsupport.GenScout.reportAndReset() + System.lineSeparator() + dev.lull.optsupport.GenScout.perlinBench()
                        + System.lineSeparator() + dev.lull.optsupport.ColumnBound.report() : "");
        String hashLine = writeChunkHashes(server) + "\n" + cpuLine;
        Path report = SleepCommand.finishRecording(server, "genbench-" + label, line + "\n" + hashLine);
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                level.getChunkSource().updateChunkForced(new ChunkPos(x0 + dx, z0 + dz), false);
            }
        }
        Path csv = server.getServerDirectory().resolve(dev.lull.SleepOut.ROOT).resolve("genbench.csv");
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
