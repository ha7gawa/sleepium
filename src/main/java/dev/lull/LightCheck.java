package dev.lull;

import java.util.Arrays;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Measurement (-Dlab.light.fixcheck=true, notes/84): is the light vanilla's incremental engine leaves after generation
 * the same as light computed from scratch for the same blocks? At the end of a genbench area, once the level's light
 * actor is idle, every chunk of the area is lit again in a fresh, private vanilla LevelLightEngine (all sections' status
 * first, light enabled, then every chunk's sources, one propagation) and the DataLayers of the chunks at least 2 chunks
 * inside the area (whose light depends only on chunks of the area) are compared nibble by nibble with the level's.
 *
 * <p>Equal = in generation the final light is a function of the blocks (a fixed point), not of the order chunks were lit
 * in: then any engine that reaches the same fixed point - in parallel, in any order - is exact. Also writes the level's
 * per-chunk light hashes (genlight-label.txt).
 */
public final class LightCheck {
    private LightCheck() {}

    public static final boolean ON = Boolean.getBoolean("lab.light.fixcheck");
    /** the alternative to compare against vanilla's from-scratch engine (the prototype plugs in here); null = vanilla */
    public static volatile Relighter ALT;
    private static int idleTicks;
    private static long waitStart;

    public interface Relighter {
        /** lights the area's chunks; returns the light engine to read layers from */
        LevelLightEngine relight(ServerLevel level, LevelChunk[] chunks, LightChunkGetter getter);
        String name();
    }

    static boolean wantsRecheck(String result) {
        return false;
    }

    /** true when the level's light actor has had nothing to do for 10 server ticks (or 30 s passed) */
    static boolean ready(ServerLevel level) {
        if (waitStart == 0) waitStart = System.nanoTime();
        var eng = level.getChunkSource().getLightEngine();
        boolean idle = !eng.hasLightWork() && (!(eng instanceof LightProbe.Engine e) || e.sleep$pendingTasks() == 0);
        idleTicks = idle ? idleTicks + 1 : 0;
        if (idleTicks >= 10 || System.nanoTime() - waitStart > 30_000_000_000L) {
            idleTicks = 0;
            waitStart = 0;
            return true;
        }
        return false;
    }

    /**
     * 1.20.1 (no /tick freeze): the check runs on the server thread, which blocks here - no tick, no block changes - until
     * every light task submitted before is processed: the sorter idle, LightDirect's queues and the light mailbox empty,
     * no queued batch, no light work left, 50 ms in a row (15 s at most). Main-thread chunk tasks keep running (the
     * light may need the server's polling to schedule its batches). Without it a lava flow of the check's own tick was
     * in the relight's blocks but not yet in the light (11-22, notes/84: the "-1 near lava" race of the check).
     */
    static String drain(ServerLevel level) {
        var eng = level.getChunkSource().getLightEngine();
        long t0 = System.nanoTime(), stableSince = 0;
        int polls = 0;
        while (System.nanoTime() - t0 < 15_000_000_000L) {
            boolean idle = !eng.hasLightWork() && dev.lull.optsupport.LightLanes.PENDING.get() == 0
                    && (!(eng instanceof LightProbe.Engine e) || e.sleep$pendingTasks() == 0 && e.sleep$mailboxSize() == 0)
                    && !((dev.lull.mixin.LightProbeChunkMapAccessor) level.getChunkSource().chunkMap).sleep$sorter().hasWork();
            long now = System.nanoTime();
            if (idle) {
                if (stableSince == 0) stableSince = now;
                if (now - stableSince > 50_000_000L) break;
            } else stableSince = 0;
            polls++;
            if (!level.getChunkSource().pollTask()) {
                eng.tryScheduleUpdate();
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
        }
        return String.format(Locale.ROOT, " (light drained in %.0f ms, %d polls)", (System.nanoTime() - t0) / 1e6, polls);
    }

    static String run(ServerLevel level, int x0, int z0, int side, java.nio.file.Path hashFile) {
        String d = Boolean.getBoolean("lab.light.fixcheck.nodrain") ? "" : drain(level);
        LightHistory.paused = true;   // (the private relight below writes block light too)
        try {
            return run0(level, x0, z0, side, hashFile) + d;
        } finally {
            LightHistory.paused = false;
        }
    }

    static String run0(ServerLevel level, int x0, int z0, int side, java.nio.file.Path hashFile) {
        long t0 = System.nanoTime();
        LevelChunk[] chunks = new LevelChunk[side * side];
        for (int dx = 0; dx < side; dx++)
            for (int dz = 0; dz < side; dz++) chunks[dx * side + dz] = level.getChunkSource().getChunkNow(x0 + dx, z0 + dz);
        LightChunkGetter getter = new LightChunkGetter() {
            @Nullable
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                int dx = x - x0, dz = z - z0;
                return dx < 0 || dz < 0 || dx >= side || dz >= side ? null : chunks[dx * side + dz];
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        };
        LevelLightEngine mine;
        String name;
        Relighter alt = ALT;
        if (alt != null) {
            mine = alt.relight(level, chunks, getter);
            name = alt.name();
        } else {
            mine = vanillaFromScratch(level, chunks, getter);
            name = "vanilla from scratch";
        }
        long t1 = System.nanoTime();
        LevelLightEngine real = level.getChunkSource().getLightEngine();
        int minS = real.getMinLightSection(), maxS = real.getMaxLightSection();
        long[] sec = new long[2], secDiff = new long[2], nib = new long[2], presence = new long[2], higher = new long[2], lower = new long[2];
        int chunksCompared = 0, chunksDiff = 0;
        StringBuilder samples = new StringBuilder();
        BlockPos firstBlockDiff = null;
        StringBuilder hashes = new StringBuilder();
        for (int dx = 0; dx < side; dx++)
            for (int dz = 0; dz < side; dz++) {
                int cx = x0 + dx, cz = z0 + dz;
                long h = 1469598103934665603L;
                boolean inner = dx >= 2 && dz >= 2 && dx < side - 2 && dz < side - 2;
                boolean differs = false;
                for (int l = 0; l < 2; l++) {
                    LightLayer layer = l == 0 ? LightLayer.BLOCK : LightLayer.SKY;
                    for (int sy = minS; sy < maxS; sy++) {
                        SectionPos sp = SectionPos.of(cx, sy, cz);
                        DataLayer a = real.getLayerListener(layer).getDataLayerData(sp);
                        h = (h ^ (a == null ? 0x1F : a.isDefinitelyHomogenous() ? 0x2F + a.get(0, 0, 0) : Arrays.hashCode(a.getData()))) * 1099511628211L;
                        if (!inner) continue;
                        DataLayer b = mine.getLayerListener(layer).getDataLayerData(sp);
                        sec[l]++;
                        if (a == null && b == null) continue;
                        int d = 0;
                        if (a == null || b == null) {
                            presence[l]++;
                            // effective values (a sky section without data reads the one above)
                            for (int i = 0; i < 4096; i++) {
                                BlockPos p = sp.origin().offset(i & 15, i >> 8, (i >> 4) & 15);
                                int va = real.getLayerListener(layer).getLightValue(p), vb = mine.getLayerListener(layer).getLightValue(p);
                                if (va != vb) {
                                    d++;
                                    if (va > vb) higher[l]++; else lower[l]++;
                                }
                            }
                        } else if (!a.isDefinitelyHomogenous() && !b.isDefinitelyHomogenous() && Arrays.equals(a.getData(), b.getData())) {
                            continue;
                        } else {
                            for (int i = 0; i < 4096; i++) {
                                int va = a.get(i & 15, i >> 8, (i >> 4) & 15), vb = b.get(i & 15, i >> 8, (i >> 4) & 15);
                                if (va != vb) {
                                    d++;
                                    if (va > vb) higher[l]++; else lower[l]++;
                                    if (l == 0 && firstBlockDiff == null) firstBlockDiff = new BlockPos(cx * 16 + (i & 15), sy * 16 + (i >> 8), cz * 16 + ((i >> 4) & 15));
                                    if (samples.length() < 1500)
                                        samples.append(String.format(Locale.ROOT, " %s %d,%d,%d real %d new %d;", l == 0 ? "block" : "sky",
                                                cx * 16 + (i & 15), sy * 16 + (i >> 8), cz * 16 + ((i >> 4) & 15), va, vb));
                                }
                            }
                        }
                        if (d > 0) {
                            secDiff[l]++;
                            nib[l] += d;
                            differs = true;
                        }
                    }
                }
                if (inner) {
                    chunksCompared++;
                    if (differs) chunksDiff++;
                }
                hashes.append(cx).append(' ').append(cz).append(' ').append(Long.toHexString(h)).append('\n');
            }
        try {
            java.nio.file.Files.writeString(hashFile, hashes.toString());
        } catch (java.io.IOException e) {
            SleepMod.LOGGER.error("Cannot write {}", hashFile, e);
        }
        if (firstBlockDiff != null) samples.append(" | emitters within 15 of ").append(firstBlockDiff.toShortString()).append(':').append(emitters(level, real, mine, firstBlockDiff));
        if (firstBlockDiff != null && LightHistory.ON) samples.append(LightHistory.explain(level, real, mine, firstBlockDiff));
        samples.append(publishCheck(real, x0, z0, side));
        long t2 = System.nanoTime();
        return String.format(Locale.ROOT, "light fixcheck (%s vs the level's light, %d inner chunks): %d chunks differ; block: %d sections, %d differ (%d nibbles: level higher %d, lower %d; data presence %d);"
                        + " sky: %d sections, %d differ (%d nibbles: level higher %d, lower %d; data presence %d); relight %.0f ms, compare %.0f ms%s",
                name, chunksCompared, chunksDiff, sec[0], secDiff[0], nib[0], higher[0], lower[0], presence[0], sec[1], secDiff[1], nib[1], higher[1], lower[1], presence[1],
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, samples.length() > 0 ? "; first:" + samples : "");
    }

    /**
     * The level's block light as the light actor holds it (the updating map) against what it published (the visible map,
     * read by everyone else and by this check): sections whose values differ, and the first differing cells. Tells a lost
     * propagation (both lower) from a lost publication (only the visible copy lower).
     */
    static String publishCheck(LevelLightEngine real, int x0, int z0, int side) {
        try {
            Object eng = ((dev.lull.mixin.LightProbeLevelEngineAccessor) real).sleep$block();
            if (eng == null) return "";
            var st = ((dev.lull.mixin.LightProbeEngineAccessor) eng).sleep$storage();
            var upd = ((dev.lull.mixin.LightProbeStorageInvoker) st).sleep$updating();
            int minS = real.getMinLightSection(), maxS = real.getMaxLightSection(), secs = 0, cells = 0;
            StringBuilder b = new StringBuilder();
            for (int dx = 2; dx < side - 2; dx++)
                for (int dz = 2; dz < side - 2; dz++)
                    for (int sy = minS; sy < maxS; sy++) {
                        long key = SectionPos.asLong(x0 + dx, sy, z0 + dz);
                        DataLayer v = real.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(key)), u = upd.getLayer(key);
                        if (v == u || v != null && u != null && Arrays.equals(v.getData(), u.getData())) continue;
                        secs++;
                        for (int i = 0; i < 4096; i++) {
                            int a = v == null ? 0 : v.get(i & 15, i >> 8, (i >> 4) & 15), c = u == null ? 0 : u.get(i & 15, i >> 8, (i >> 4) & 15);
                            if (a != c) {
                                cells++;
                                if (b.length() < 400) b.append(String.format(Locale.ROOT, " %d,%d,%d visible %d updating %d;", (x0 + dx) * 16 + (i & 15), sy * 16 + (i >> 8), (z0 + dz) * 16 + ((i >> 4) & 15), a, c));
                            }
                        }
                    }
            return " | published vs actor's block light: " + secs + " sections, " + cells + " cells differ" + (b.length() > 0 ? ":" + b : "");
        } catch (Throwable t) {
            return " | published check failed: " + t;
        }
    }

    /** the light sources around a differing cell: state, emission, the level's and the relight's block light there (what was not propagated) */
    static String emitters(ServerLevel level, LevelLightEngine real, LevelLightEngine mine, BlockPos at) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -15; dx <= 15; dx++)
            for (int dy = -15; dy <= 15; dy++)
                for (int dz = -15; dz <= 15; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 15) continue;
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    var st = level.getBlockState(p);
                    int em = st.getLightEmission();
                    if (em == 0) continue;
                    if (n++ < 16)
                        b.append(String.format(Locale.ROOT, " %s %s em %d real %d new %d;", p.toShortString(), st, em,
                                real.getLayerListener(LightLayer.BLOCK).getLightValue(p), mine.getLayerListener(LightLayer.BLOCK).getLightValue(p)));
                }
        return b.append(" (").append(n).append(" sources)").toString();
    }

    /** vanilla's engine from scratch: the order of initializeLight (pre, post) then lightChunk of every chunk, one propagation */
    static LevelLightEngine vanillaFromScratch(ServerLevel level, LevelChunk[] chunks, LightChunkGetter getter) {
        LevelLightEngine e = new LevelLightEngine(getter, true, level.dimensionType().hasSkyLight());
        for (LevelChunk c : chunks) {
            if (c == null) continue;
            LevelChunkSection[] s = c.getSections();
            for (int i = 0; i < s.length; i++)
                if (!s[i].hasOnlyAir()) e.updateSectionStatus(SectionPos.of(c.getPos(), level.getSectionYFromSectionIndex(i)), false);
        }
        for (LevelChunk c : chunks) {
            if (c == null) continue;
            e.setLightEnabled(c.getPos(), true);
            e.retainData(c.getPos(), false);
        }
        e.runLightUpdates();
        for (LevelChunk c : chunks) {
            if (c == null) continue;
            e.propagateLightSources(c.getPos());
        }
        e.runLightUpdates();
        return e;
    }
}
