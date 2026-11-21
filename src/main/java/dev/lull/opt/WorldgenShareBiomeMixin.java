package dev.lull.opt;

import dev.lull.optsupport.BiomeConfine;
import dev.lull.optsupport.BiomeFiddleCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldgenShare (biomes): BiomeManager.getBiome picks the nearest of the 8 quart corners around a block, each moved by a
 * "fiddle" offset hashed from the seed and the corner (8 LCG steps and 3 offsets per corner, 8 corners per call). The
 * 64 blocks of one quart cell share those 8 corners, so the offsets are kept for the last cell and the distances are
 * computed with the same expression in the same order: the same doubles, the same corner.
 *
 * <p>Only for managers one thread uses: a WorldGenRegion's (one per step application) and the managers derived from it
 * with withDifferentSource (surface building). Shared managers (levels, the client) stay vanilla.
 */
@Mixin(BiomeManager.class)
public abstract class WorldgenShareBiomeMixin implements BiomeConfine {
    @Shadow @Final private BiomeManager.NoiseBiomeSource noiseBiomeSource;
    @Shadow @Final private long biomeZoomSeed;

    @Shadow
    private static double getFiddle(long seed) {
        throw new AssertionError();
    }

    @Shadow
    private static double getFiddledDistance(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        throw new AssertionError();
    }

    @Unique private boolean sleep$confined;
    @Unique private int sleep$l = Integer.MIN_VALUE, sleep$i1, sleep$j1;
    /** per corner (bit order as vanilla's loop index): x, y, z offsets */
    @Unique private double[] sleep$fid;

    @Override
    public void sleep$confine() {
        sleep$confined = true;
    }

    @Inject(method = "withDifferentSource", at = @At("RETURN"))
    private void sleep$inherit(BiomeManager.NoiseBiomeSource newSource, CallbackInfoReturnable<BiomeManager> cir) {
        if (sleep$confined) ((BiomeConfine) cir.getReturnValue()).sleep$confine();
    }

    @Unique
    private void sleep$fill(int l, int i1, int j1) {
        if (sleep$fid == null) sleep$fid = new double[24];
        long seed = this.biomeZoomSeed;
        if (BiomeFiddleCache.ON) {   // a pure function of (seed, cell): kept per thread for 256 cells (BiomeFiddleCache)
            if (BiomeFiddleCache.get(seed, l, i1, j1, sleep$fid)) {
                if (BiomeFiddleCache.VERIFY) {
                    double[] cached = sleep$fid.clone();
                    sleep$compute(seed, l, i1, j1);
                    BiomeFiddleCache.check(cached, sleep$fid);
                }
                sleep$l = l;
                sleep$i1 = i1;
                sleep$j1 = j1;
                return;
            }
            sleep$compute(seed, l, i1, j1);
            BiomeFiddleCache.put(seed, l, i1, j1, sleep$fid);
            sleep$l = l;
            sleep$i1 = i1;
            sleep$j1 = j1;
            return;
        }
        sleep$compute(seed, l, i1, j1);
        sleep$l = l;
        sleep$i1 = i1;
        sleep$j1 = j1;
    }

    @Unique
    private void sleep$compute(long seed, int l, int i1, int j1) {
        for (int l1 = 0; l1 < 8; l1++) {
            int x = (l1 & 4) == 0 ? l : l + 1;
            int y = (l1 & 2) == 0 ? i1 : i1 + 1;
            int z = (l1 & 1) == 0 ? j1 : j1 + 1;
            long s = LinearCongruentialGenerator.next(seed, (long) x);
            s = LinearCongruentialGenerator.next(s, (long) y);
            s = LinearCongruentialGenerator.next(s, (long) z);
            s = LinearCongruentialGenerator.next(s, (long) x);
            s = LinearCongruentialGenerator.next(s, (long) y);
            s = LinearCongruentialGenerator.next(s, (long) z);
            sleep$fid[l1 * 3] = getFiddle(s);
            s = LinearCongruentialGenerator.next(s, seed);
            sleep$fid[l1 * 3 + 1] = getFiddle(s);
            s = LinearCongruentialGenerator.next(s, seed);
            sleep$fid[l1 * 3 + 2] = getFiddle(s);
        }
    }

    /**
     * @author probe
     * @reason the same corner choice with the corners' fiddle offsets kept per quart cell (confined managers only)
     */
    @Overwrite
    public Holder<Biome> getBiome(BlockPos pos) {
        int i = pos.getX() - 2;
        int j = pos.getY() - 2;
        int k = pos.getZ() - 2;
        int l = i >> 2;
        int i1 = j >> 2;
        int j1 = k >> 2;
        double d0 = (double) (i & 3) / 4.0;
        double d1 = (double) (j & 3) / 4.0;
        double d2 = (double) (k & 3) / 4.0;
        int k1 = 0;
        double d3 = Double.POSITIVE_INFINITY;
        boolean memo = sleep$confined;
        if (memo && (l != sleep$l || i1 != sleep$i1 || j1 != sleep$j1)) sleep$fill(l, i1, j1);

        for (int l1 = 0; l1 < 8; l1++) {
            boolean flag = (l1 & 4) == 0;
            boolean flag1 = (l1 & 2) == 0;
            boolean flag2 = (l1 & 1) == 0;
            double d4 = flag ? d0 : d0 - 1.0;
            double d5 = flag1 ? d1 : d1 - 1.0;
            double d6 = flag2 ? d2 : d2 - 1.0;
            double d7;
            if (memo) {
                double[] f = sleep$fid;
                // getFiddledDistance: square(zNoise + d2) + square(yNoise + d1) + square(xNoise + d0)
                d7 = Mth.square(d6 + f[l1 * 3 + 2]) + Mth.square(d5 + f[l1 * 3 + 1]) + Mth.square(d4 + f[l1 * 3]);
            } else {
                int i2 = flag ? l : l + 1;
                int j2 = flag1 ? i1 : i1 + 1;
                int k2 = flag2 ? j1 : j1 + 1;
                d7 = getFiddledDistance(this.biomeZoomSeed, i2, j2, k2, d4, d5, d6);
            }
            if (d3 > d7) {
                k1 = l1;
                d3 = d7;
            }
        }

        int l2 = (k1 & 4) == 0 ? l : l + 1;
        int i3 = (k1 & 2) == 0 ? i1 : i1 + 1;
        int j3 = (k1 & 1) == 0 ? j1 : j1 + 1;
        return this.noiseBiomeSource.getNoiseBiome(l2, i3, j3);
    }
}
