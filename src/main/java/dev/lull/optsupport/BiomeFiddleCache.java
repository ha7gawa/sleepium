package dev.lull.optsupport;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BiomeFiddle (-Dlab.opt.BiomeFiddleCache, with WorldgenShareBiomeMixin): BiomeManager.getBiome's 8 corner offsets of
 * a quart cell (8 x 8 LCG steps and 24 getFiddle) are a pure function of (biomeZoomSeed, cell x, y, z) - nothing else
 * enters WorldgenShareBiomeMixin.sleep$fill. The manager keeps only its last cell; features ask cells again after
 * other cells (trees, then flowers, grass, patches at the same surface cells; the 3 x 3 neighbourhood of a step's
 * placements). Here a per-thread direct-mapped table of 256 cells keyed by all four values; a hit copies the 24
 * doubles into the manager's own array (no sharing between managers), a miss computes them with vanilla's expressions
 * and stores a copy. Same doubles, so the same corner is chosen.
 *
 * <p>-Dlab.featcpu.verify=true: every hit is recomputed by the caller and compared; hit / miss counts printed.
 */
public final class BiomeFiddleCache {
    public static final boolean ON = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.BiomeFiddleCache");
    public static final boolean VERIFY = Boolean.getBoolean("lab.featcpu.verify");
    private static final int SLOTS = 256;
    private static final AtomicLong HITS = new AtomicLong(), MISSES = new AtomicLong(), BAD = new AtomicLong();
    private static final ThreadLocal<BiomeFiddleCache> TL = ThreadLocal.withInitial(BiomeFiddleCache::new);

    private final long[] seeds = new long[SLOTS];
    private final int[] xs = new int[SLOTS], ys = new int[SLOTS], zs = new int[SLOTS];
    private final boolean[] used = new boolean[SLOTS];
    private final double[] values = new double[SLOTS * 24];

    private BiomeFiddleCache() {}

    private static int slot(long seed, int x, int y, int z) {
        int h = (x * 0x9E3779B1) ^ (y * 0x85EBCA77) ^ (z * 0xC2B2AE3D) ^ (int) seed;
        return (h ^ (h >>> 16)) & (SLOTS - 1);
    }

    /** the cell's offsets into out (24 doubles) when cached */
    public static boolean get(long seed, int x, int y, int z, double[] out) {
        BiomeFiddleCache c = TL.get();
        int s = slot(seed, x, y, z);
        if (c.used[s] && c.xs[s] == x && c.zs[s] == z && c.ys[s] == y && c.seeds[s] == seed) {
            System.arraycopy(c.values, s * 24, out, 0, 24);
            if (VERIFY) HITS.incrementAndGet();
            return true;
        }
        if (VERIFY) MISSES.incrementAndGet();
        return false;
    }

    public static void put(long seed, int x, int y, int z, double[] in) {
        BiomeFiddleCache c = TL.get();
        int s = slot(seed, x, y, z);
        c.used[s] = true;
        c.seeds[s] = seed;
        c.xs[s] = x;
        c.ys[s] = y;
        c.zs[s] = z;
        System.arraycopy(in, 0, c.values, s * 24, 24);
    }

    /** verify: a hit's doubles against a fresh computation */
    public static void check(double[] cached, double[] fresh) {
        if (!Arrays.equals(cached, fresh) && BAD.incrementAndGet() <= 20)
            System.out.println("[sleep/featcpu] FIDDLE MISMATCH " + Arrays.toString(cached) + " vs " + Arrays.toString(fresh));
        long h = HITS.get();
        if ((h & ((1 << 20) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
    }

    public static String summary() {
        return "fiddle cells " + HITS.get() + " hits, " + MISSES.get() + " misses, " + BAD.get() + " mismatches";
    }
}
