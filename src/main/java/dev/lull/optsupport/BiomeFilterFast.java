package dev.lull.optsupport;

import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * BiomeFilterFast (-Dlab.opt.BiomeFilterFastMixin): BiomeFilter.shouldPlace answers {@code
 * generator.getBiomeGenerationSettings(level.getBiome(pos)).hasFeature(feature)}; getBiome hashes 8 fiddled corners
 * and picks one (~10 % of FEATURES: ores alone test ~1000 positions a chunk). When the answer is the same for every
 * biome getBiome can return there, it does not matter which one it returns.
 *
 * <p>Which biomes can it return: in a WorldGenRegion getBiome is BiomeManager.getBiome with the region as noise source:
 * the noise biome of one of the 8 quart corners (lx or lx + 1, ly or ly + 1, lz or lz + 1; l = (block - 2) >> 2) -
 * LevelReader.getNoiseBiome -> the region's chunk at (qx >> 2, qz >> 2) (the same chunk object getChunk(cx, cz) gives
 * for any requested status up to the dependency) -> ChunkAccess.getNoiseBiome: qy clamped to [minY >> 2, (minY >> 2) +
 * (height >> 2) - 1], section index (qy << 2 >> 4) - (minY >> 4), an entry of that section's biome container. So the
 * answer is one of the values present in the biome containers of the (at most 2 x 2) chunks of the corner columns at
 * the (at most 2) sections of the clamped corner heights. Per region each such section's values are a bit mask over
 * the region's biome list (getAll: exactly the values present); per feature, the mask of biomes whose settings have
 * it. The union U of the corners' section masks decides: U inside the feature mask -> true, U disjoint from it ->
 * false, else vanilla's lines. Only when all corner chunks are at most 1 from the region's centre (the 3 x 3 the
 * FEATURES step reads), in the FEATURES step (biome containers are written in the BIOMES step only, so the masks stay
 * true for the region's life), with at most 64 biomes and equal heights in the 9 chunks; else vanilla's lines.
 * hasFeature reads an immutable set; skipping getBiome only skips WorldgenShareBiomeMixin's memo fill (a cache).
 *
 * <p>Per thread: the current region's masks; feature masks are kept while the next region has the same biome list.
 * -Dlab.featcpu.verify=true: every shortcut answer is compared with vanilla's.
 */
public final class BiomeFilterFast {
    public static final boolean VERIFY = Boolean.getBoolean("lab.featcpu.verify");
    private static final AtomicLong FAST = new AtomicLong(), SLOW = new AtomicLong(), BAD = new AtomicLong();
    private static final ThreadLocal<BiomeFilterFast> TL = ThreadLocal.withInitial(BiomeFilterFast::new);

    private WeakReference<WorldGenRegion> region;
    private ChunkGenerator generator;
    private int cx, cz, minQ, maxQ, minSec, nSec;
    private boolean usable;
    private final List<Holder<Biome>> biomes = new ArrayList<>(), previous = new ArrayList<>();
    /** [chunk (dx + 1) * 3 + (dz + 1)][section]: the biomes present, as bits of the biome list */
    private long[][] masks = new long[9][0];
    private final Reference2LongOpenHashMap<PlacedFeature> featureMasks = new Reference2LongOpenHashMap<>();

    private BiomeFilterFast() {
        featureMasks.defaultReturnValue(-1L);
    }

    /** 1 true, 0 false, -1 not decided here (vanilla's lines) */
    public static int decide(WorldGenRegion level, ChunkGenerator generator, PlacedFeature feature, BlockPos pos) {
        BiomeFilterFast f = TL.get();
        if (f.region == null || f.region.get() != level || f.generator != generator) f.enter(level, generator);
        if (!f.usable) return -1;
        int lx = (pos.getX() - 2) >> 2, ly = (pos.getY() - 2) >> 2, lz = (pos.getZ() - 2) >> 2;
        int x0 = (lx >> 2) - f.cx + 1, x1 = ((lx + 1) >> 2) - f.cx + 1, z0 = (lz >> 2) - f.cz + 1, z1 = ((lz + 1) >> 2) - f.cz + 1;
        if (x0 < 0 || x1 > 2 || z0 < 0 || z1 > 2) return -1;
        int j0 = (Mth.clamp(ly, f.minQ, f.maxQ) >> 2) - f.minSec, j1 = (Mth.clamp(ly + 1, f.minQ, f.maxQ) >> 2) - f.minSec;
        if (j0 < 0 || j1 >= f.nSec) return -1;
        long[][] m = f.masks;
        long u = m[x0 * 3 + z0][j0] | m[x0 * 3 + z0][j1] | m[x0 * 3 + z1][j0] | m[x0 * 3 + z1][j1]
                | m[x1 * 3 + z0][j0] | m[x1 * 3 + z0][j1] | m[x1 * 3 + z1][j0] | m[x1 * 3 + z1][j1];
        long fm = f.featureMasks.getLong(feature);
        if (fm == -1L) {
            fm = 0L;
            for (int i = 0; i < f.biomes.size(); i++)
                if (generator.getBiomeGenerationSettings(f.biomes.get(i)).hasFeature(feature)) fm |= 1L << i;
            f.featureMasks.put(feature, fm);
        }
        if ((u & ~fm) == 0L) return 1;
        if ((u & fm) == 0L) return 0;
        return -1;
    }

    private void enter(WorldGenRegion level, ChunkGenerator generator) {
        region = new WeakReference<>(level);
        previous.clear();
        previous.addAll(biomes);
        biomes.clear();
        usable = false;
        ChunkPos c = level.getCenter();
        cx = c.x;
        cz = c.z;
        boolean ok = ((dev.lull.opt.RegionStepAccessor) level).sleep$step().targetStatus() == ChunkStatus.FEATURES;
        for (int dx = -1; ok && dx <= 1; dx++)
            for (int dz = -1; ok && dz <= 1; dz++) {
                ChunkAccess chunk = level.getChunk(cx + dx, cz + dz);
                int minY = chunk.getMinBuildHeight(), height = chunk.getHeight();
                int mq = minY >> 2, xq = mq + (height >> 2) - 1, ms = minY >> 4;
                LevelChunkSection[] sections = chunk.getSections();
                if (dx == -1 && dz == -1) {
                    minQ = mq;
                    maxQ = xq;
                    minSec = ms;
                    nSec = sections.length;
                } else if (mq != minQ || xq != maxQ || ms != minSec || sections.length != nSec) ok = false;
                if (!ok) break;
                int ci = (dx + 1) * 3 + (dz + 1);
                if (masks[ci].length != nSec) masks[ci] = new long[nSec];
                for (int j = 0; j < nSec; j++) {
                    long[] bits = {0L};
                    sections[j].getBiomes().getAll(b -> {
                        int i = biomes.indexOf(b);   // Holder.Reference: identity equals
                        if (i < 0) {
                            i = biomes.size();
                            biomes.add(b);
                        }
                        if (i < 64) bits[0] |= 1L << i;
                    });
                    masks[ci][j] = bits[0];
                }
                if (biomes.size() > 64) ok = false;
            }
        // the corner sections' indices are inside [0, nSec) whenever the clamped heights are inside the chunks
        usable = ok && ((maxQ >> 2) - minSec) < nSec && ((minQ >> 2) - minSec) >= 0;
        if (generator != this.generator || !biomes.equals(previous)) featureMasks.clear();
        this.generator = generator;
    }

    public static void count(boolean fast) {
        (fast ? FAST : SLOW).incrementAndGet();
    }

    public static void check(boolean ours, boolean vanilla, PlacedFeature feature, BlockPos pos) {
        if (ours != vanilla && BAD.incrementAndGet() <= 20)
            System.out.println("[sleep/featcpu] BIOME FILTER MISMATCH at " + pos + ": vanilla " + vanilla + " ours " + ours + " " + feature);
        long n = FAST.get();
        if ((n & ((1 << 20) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
    }

    public static String summary() {
        return "biome filter " + FAST.get() + " decided by the corner sections, " + SLOW.get() + " by getBiome, " + BAD.get() + " mismatches";
    }
}
