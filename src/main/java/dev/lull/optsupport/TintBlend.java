package dev.lull.optsupport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;

/**
 * -Dlab.tintBlend (11-17): biome blending by running sums. Vanilla's ClientLevel.calculateBlockTint averages the
 * resolver's colour over the (2r+1)^2 positions around a block - for each block anew (r = 7, the slider's top: 225
 * biome lookups with BiomeManager's fuzzy zoom per block; the zoo orbit at distance 32 spent ~20 ms of a worker per
 * meshed section, 60 % of the meshing workers' samples in getNoiseBiome). The average is a box sum: the first miss in a
 * BlockTintCache layer (16 x 16 blocks at one y) fills the whole layer from (16+2r)^2 samples with sliding sums - the
 * same integer sums, the same division, so the same colours bit for bit (-Dlab.tintBlend.verify=true compares every
 * filled entry with vanilla's calculation).
 */
public final class TintBlend {
    private TintBlend() {}

    public static final boolean ON = Boolean.getBoolean("lab.tintBlend");
    public static final boolean VERIFY = Boolean.getBoolean("lab.tintBlend.verify");
    public static final java.util.concurrent.atomic.AtomicLong LAYERS = new java.util.concurrent.atomic.AtomicLong(),
            FILLED = new java.util.concurrent.atomic.AtomicLong(), UNSTABLE = new java.util.concurrent.atomic.AtomicLong(), MISMATCHES = new java.util.concurrent.atomic.AtomicLong();

    /** BlockTintCache: its level and resolver (ClientLevel binds every cache it made) */
    public interface Bound {
        void sleep$bind(ClientLevel level, ColorResolver resolver);

        ClientLevel sleep$level();

        ColorResolver sleep$resolver();
    }

    private static final class Scratch {
        int[] col = new int[0], rowR = new int[0], rowG = new int[0], rowB = new int[0];
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double[] fx = new double[0], fy = new double[0], fz = new double[0];
        Object[] biome = new Object[0];
        boolean[] done = new boolean[0];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * A miss at pos in layer (its section column's 16 x 16 at pos's y): the layer's empty entries filled; returns pos's
     * colour, or Integer.MIN_VALUE when the caller should compute it the vanilla way (no blending, unbound cache).
     */
    public static int fill(Object cache, int[] layer, BlockPos pos) {
        if (!(cache instanceof Bound b) || b.sleep$level() == null) return Integer.MIN_VALUE;
        int rad = Minecraft.getInstance().options.biomeBlendRadius().get();
        if (rad <= 0) return Integer.MIN_VALUE;
        ClientLevel level = b.sleep$level();
        ColorResolver res = b.sleep$resolver();
        int x0 = (pos.getX() & ~15) - rad, z0 = (pos.getZ() & ~15) - rad, y = pos.getY();
        int filled = fillInto(level, res, rad, x0, z0, y, layer);
        LAYERS.incrementAndGet();
        FILLED.addAndGet(filled);
        if (VERIFY) {   // vanilla before and after the layer: a chunk loading meanwhile (both differ) is no mismatch
            int bx = x0 + rad, bz = z0 + rad;
            int[] v1 = new int[256];
            for (int i = 0; i < 256; i++) v1[i] = level.calculateBlockTint(new BlockPos(bx + (i & 15), y, bz + (i >> 4)), res);
            int[] ours = new int[256];
            java.util.Arrays.fill(ours, -1);
            fillInto(level, res, rad, x0, z0, y, ours);
            for (int i = 0; i < 256; i++) {
                int v2 = level.calculateBlockTint(new BlockPos(bx + (i & 15), y, bz + (i >> 4)), res);
                if (v1[i] != v2) {
                    UNSTABLE.incrementAndGet();
                    continue;
                }
                if (v2 != ours[i] && MISMATCHES.incrementAndGet() <= 5)
                    System.out.println("[sleep/opt] tint blend MISMATCH at " + (bx + (i & 15)) + " " + y + " " + (bz + (i >> 4)) + ": " + Integer.toHexString(ours[i]) + " vs vanilla " + Integer.toHexString(v2));
            }
        }
        return layer[(pos.getZ() & 15) << 4 | (pos.getX() & 15)];
    }

    /** the layer at (x0 + rad, y, z0 + rad)'s 16 x 16: its -1 entries filled by running sums; returns how many */
    private static int fillInto(ClientLevel level, ColorResolver res, int rad, int x0, int z0, int y, int[] layer) {
        int w = 2 * rad + 1, s = 16 + 2 * rad, n = w * w;
        Scratch sc = SCRATCH.get();
        if (sc.col.length < s * s) {
            sc.col = new int[s * s];
            sc.rowR = new int[s * 16];
            sc.rowG = new int[s * 16];
            sc.rowB = new int[s * 16];
        }
        int[] col = sc.col, rr = sc.rowR, rg = sc.rowG, rb = sc.rowB;
        BlockPos.MutableBlockPos m = sc.m;
        if (!ZOOM || !zoomed(level, res, x0, z0, y, s, col, sc))
            for (int dz = 0; dz < s; dz++)
                for (int dx = 0; dx < s; dx++) {
                    m.set(x0 + dx, y, z0 + dz);
                    col[dz * s + dx] = res.getColor(level.getBiome(m).value(), (double) m.getX(), (double) m.getZ());
                }
        // along x: each row's window sums for the 16 output columns
        for (int dz = 0; dz < s; dz++) {
            int r = 0, g = 0, bl = 0, base = dz * s;
            for (int dx = 0; dx < w; dx++) {
                int c = col[base + dx];
                r += (c & 0xFF0000) >> 16;
                g += (c & 0xFF00) >> 8;
                bl += c & 0xFF;
            }
            for (int x = 0; ; x++) {
                rr[dz * 16 + x] = r;
                rg[dz * 16 + x] = g;
                rb[dz * 16 + x] = bl;
                if (x == 15) break;
                int out = col[base + x], in = col[base + x + w];
                r += ((in & 0xFF0000) >> 16) - ((out & 0xFF0000) >> 16);
                g += ((in & 0xFF00) >> 8) - ((out & 0xFF00) >> 8);
                bl += (in & 0xFF) - (out & 0xFF);
            }
        }
        // along z: the rows' sums over each output row's window
        int filled = 0;
        for (int x = 0; x < 16; x++) {
            int r = 0, g = 0, bl = 0;
            for (int dz = 0; dz < w; dz++) {
                r += rr[dz * 16 + x];
                g += rg[dz * 16 + x];
                bl += rb[dz * 16 + x];
            }
            for (int z = 0; ; z++) {
                int idx = z << 4 | x;
                if (layer[idx] == -1) {
                    layer[idx] = (r / n & 0xFF) << 16 | (g / n & 0xFF) << 8 | bl / n & 0xFF;
                    filled++;
                }
                if (z == 15) break;
                r += rr[(z + w) * 16 + x] - rr[z * 16 + x];
                g += rg[(z + w) * 16 + x] - rg[z * 16 + x];
                bl += rb[(z + w) * 16 + x] - rb[z * 16 + x];
            }
        }
        return filled;
    }

    /**
     * -Dlab.tintBlend.zoom (11-18): the layer's biomes by BiomeManager.getBiome's own arithmetic, with what it repeats for
     * every block worked out once: a quart corner's three fiddles (8 LCG steps each time, shared by the 4 x 4 x 4 blocks
     * around the corner) and a quart cell's noise biome (a chunk lookup each time). The same doubles in the same order:
     * the same corner picked, the same biome. Only for the vanilla biome manager reading the level itself.
     */
    public static final boolean ZOOM = Boolean.getBoolean("lab.tintBlend.zoom");

    private static boolean zoomed(ClientLevel level, ColorResolver res, int x0, int z0, int y, int s, int[] col, Scratch sc) {
        net.minecraft.world.level.biome.BiomeManager bm = level.getBiomeManager();
        if (bm.getClass() != net.minecraft.world.level.biome.BiomeManager.class) return false;
        dev.lull.opt.TintBlendBiomeAccessor acc = (dev.lull.opt.TintBlendBiomeAccessor) (Object) bm;
        if (acc.sleep$source() != level) return false;
        long seed = acc.sleep$zoomSeed();
        // the quart box the layer's blocks reach: getBiome looks at (b - 2) >> 2 and that + 1 on each axis
        int qx0 = (x0 - 2) >> 2, qz0 = (z0 - 2) >> 2, qy0 = (y - 2) >> 2;
        int qw = ((x0 + s - 1 - 2) >> 2) - qx0 + 2, qd = ((z0 + s - 1 - 2) >> 2) - qz0 + 2;
        int cells = qw * qd * 2;
        if (sc.fx.length < cells) {
            sc.fx = new double[cells];
            sc.fy = new double[cells];
            sc.fz = new double[cells];
            sc.biome = new Object[cells];
            sc.done = new boolean[cells];
        }
        java.util.Arrays.fill(sc.done, 0, cells, false);
        java.util.Arrays.fill(sc.biome, 0, cells, null);
        double[] fx = sc.fx, fy = sc.fy, fz = sc.fz;
        Object[] biome = sc.biome;
        boolean[] done = sc.done;
        int j = y - 2;
        int i1 = j >> 2;
        double d1 = (double) (j & 3) / 4.0;
        for (int dz = 0; dz < s; dz++)
            for (int dx = 0; dx < s; dx++) {
                int bx = x0 + dx, bz = z0 + dz;
                int i = bx - 2, k = bz - 2;
                int l = i >> 2, j1 = k >> 2;
                double d0 = (double) (i & 3) / 4.0;
                double d2 = (double) (k & 3) / 4.0;
                int k1 = 0;
                double d3 = Double.POSITIVE_INFINITY;
                for (int l1 = 0; l1 < 8; l1++) {
                    boolean flag = (l1 & 4) == 0, flag1 = (l1 & 2) == 0, flag2 = (l1 & 1) == 0;
                    int i2 = flag ? l : l + 1, j2 = flag1 ? i1 : i1 + 1, k2 = flag2 ? j1 : j1 + 1;
                    double d4 = flag ? d0 : d0 - 1.0, d5 = flag1 ? d1 : d1 - 1.0, d6 = flag2 ? d2 : d2 - 1.0;
                    int c = ((k2 - qz0) * qw + (i2 - qx0)) * 2 + (j2 - qy0);
                    if (!done[c]) {   // BiomeManager.getFiddledDistance's three fiddles for this corner
                        long r = net.minecraft.util.LinearCongruentialGenerator.next(seed, (long) i2);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, (long) j2);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, (long) k2);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, (long) i2);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, (long) j2);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, (long) k2);
                        fx[c] = fiddle(r);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, seed);
                        fy[c] = fiddle(r);
                        r = net.minecraft.util.LinearCongruentialGenerator.next(r, seed);
                        fz[c] = fiddle(r);
                        done[c] = true;
                    }
                    double d7 = net.minecraft.util.Mth.square(d6 + fz[c]) + net.minecraft.util.Mth.square(d5 + fy[c]) + net.minecraft.util.Mth.square(d4 + fx[c]);
                    if (d3 > d7) {
                        k1 = l1;
                        d3 = d7;
                    }
                }
                int l2 = (k1 & 4) == 0 ? l : l + 1, i3 = (k1 & 2) == 0 ? i1 : i1 + 1, j3 = (k1 & 1) == 0 ? j1 : j1 + 1;
                int c = ((j3 - qz0) * qw + (l2 - qx0)) * 2 + (i3 - qy0);
                Object b = biome[c];
                if (b == null) biome[c] = b = level.getNoiseBiome(l2, i3, j3);
                @SuppressWarnings("unchecked")
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> h = (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>) b;
                col[dz * s + dx] = res.getColor(h.value(), (double) bx, (double) bz);
            }
        ZOOMED.incrementAndGet();
        return true;
    }

    private static double fiddle(long seed) {   // BiomeManager.getFiddle
        double d0 = (double) Math.floorMod(seed >> 24, 1024) / 1024.0;
        return (d0 - 0.5) * 0.9;
    }

    private static final java.util.concurrent.atomic.AtomicLong ZOOMED = new java.util.concurrent.atomic.AtomicLong();

    public static String report() {
        return String.format(java.util.Locale.ROOT, "tint blend: layers %d (%d by the kept zoom), entries filled %d%s", LAYERS.get(), ZOOMED.get(), FILLED.get(),
                VERIFY ? ", mismatches with vanilla " + MISMATCHES.get() + " (a chunk loading meanwhile " + UNSTABLE.get() + ")" : "");
    }
}
