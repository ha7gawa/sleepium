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

    public static String report() {
        return String.format(java.util.Locale.ROOT, "tint blend: layers %d, entries filled %d%s", LAYERS.get(), FILLED.get(),
                VERIFY ? ", mismatches with vanilla " + MISMATCHES.get() + " (a chunk loading meanwhile " + UNSTABLE.get() + ")" : "");
    }
}
