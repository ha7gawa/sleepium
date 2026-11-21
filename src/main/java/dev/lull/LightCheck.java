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

    static String run(ServerLevel level, int x0, int z0, int side, java.nio.file.Path hashFile) {
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
        } else if (Boolean.getBoolean("lab.light.fixcheck.par") && dev.lull.optsupport.LightPar.ENABLED) {
            // the same relight twice: vanilla's serial engine (timed, thrown away), then with LightPar's parallel increase pass
            vanillaFromScratch(level, chunks, getter);
            long tv = System.nanoTime();
            vanillaFromScratch(level, chunks, getter);
            tv = System.nanoTime() - tv;
            long par0 = dev.lull.optsupport.LightPar.NANOS.sum(), helped0 = dev.lull.optsupport.LightPar.HELPED.sum();
            long tp = System.nanoTime();
            dev.lull.optsupport.LightPar.forced = Thread.currentThread();
            try {
                mine = vanillaFromScratch(level, chunks, getter);
            } finally {
                dev.lull.optsupport.LightPar.forced = null;
            }
            tp = System.nanoTime() - tp;
            name = String.format(java.util.Locale.ROOT, "LightPar from scratch (relight %.0f ms, increase passes %.0f ms, %d groups by helpers; vanilla serial relight %.0f ms)",
                    tp / 1e6, (dev.lull.optsupport.LightPar.NANOS.sum() - par0) / 1e6, dev.lull.optsupport.LightPar.HELPED.sum() - helped0, tv / 1e6);
            t0 = System.nanoTime() - tp;
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
        long t2 = System.nanoTime();
        return String.format(Locale.ROOT, "light fixcheck (%s vs the level's light, %d inner chunks): %d chunks differ; block: %d sections, %d differ (%d nibbles: level higher %d, lower %d; data presence %d);"
                        + " sky: %d sections, %d differ (%d nibbles: level higher %d, lower %d; data presence %d); relight %.0f ms, compare %.0f ms%s",
                name, chunksCompared, chunksDiff, sec[0], secDiff[0], nib[0], higher[0], lower[0], presence[0], sec[1], secDiff[1], nib[1], higher[1], lower[1], presence[1],
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, samples.length() > 0 ? "; first:" + samples : "");
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
