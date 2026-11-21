package dev.lull.opt;

import dev.lull.optsupport.OreScratch;
import java.util.BitSet;
import java.util.List;
import java.util.RandomAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * WorldgenFeatureAlloc (ores): OreFeature.doPlace with its temporaries from OreScratch (see there) instead of fresh
 * ones per call. The body is vanilla's, statement for statement: the same random draws in the same order (nextDouble
 * per blob, then per candidate block the targets' tests and canPlaceOre's draws), the same bit set dedupe, the same
 * ensureCanWrite / getSection / getBlockState / setBlockState calls in the same order - canPlaceOre (vanilla's own,
 * public static, so others' changes to it still apply) gets the scratch as its block state function: it is
 * BulkSectionAccess.getBlockState over the same sections. The targets are walked by index when the list is
 * RandomAccess (the same elements in the same order, without an iterator per block).
 */
@Mixin(OreFeature.class)
public abstract class WorldgenFeatureOreMixin {
    /**
     * @author probe
     * @reason vanilla's doPlace without per-call temporaries (bit set, arrays, section map, a method reference per block)
     */
    @Overwrite
    protected boolean doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config, double minX, double maxX, double minZ,
                              double maxZ, double minY, double maxY, int x, int y, int z, int width, int height) {
        int i = 0;
        OreScratch scratch = OreScratch.take(level);
        try {
            BitSet bitset = scratch.bits;
            BlockPos.MutableBlockPos blockpos$mutableblockpos = scratch.pos;
            int j = config.size;
            double[] adouble = scratch.values(j * 4);

            for (int k = 0; k < j; k++) {
                float f = (float) k / (float) j;
                double d0 = Mth.lerp((double) f, minX, maxX);
                double d1 = Mth.lerp((double) f, minY, maxY);
                double d2 = Mth.lerp((double) f, minZ, maxZ);
                double d3 = random.nextDouble() * (double) j / 16.0;
                double d4 = ((double) (Mth.sin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
                adouble[k * 4 + 0] = d0;
                adouble[k * 4 + 1] = d1;
                adouble[k * 4 + 2] = d2;
                adouble[k * 4 + 3] = d4;
            }

            for (int l3 = 0; l3 < j - 1; l3++) {
                if (!(adouble[l3 * 4 + 3] <= 0.0)) {
                    for (int i4 = l3 + 1; i4 < j; i4++) {
                        if (!(adouble[i4 * 4 + 3] <= 0.0)) {
                            double d8 = adouble[l3 * 4 + 0] - adouble[i4 * 4 + 0];
                            double d10 = adouble[l3 * 4 + 1] - adouble[i4 * 4 + 1];
                            double d12 = adouble[l3 * 4 + 2] - adouble[i4 * 4 + 2];
                            double d14 = adouble[l3 * 4 + 3] - adouble[i4 * 4 + 3];
                            if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                                if (d14 > 0.0) {
                                    adouble[i4 * 4 + 3] = -1.0;
                                } else {
                                    adouble[l3 * 4 + 3] = -1.0;
                                }
                            }
                        }
                    }
                }
            }

            List<OreConfiguration.TargetBlockState> targets = config.targetStates;
            boolean indexed = targets instanceof RandomAccess;
            int nt = targets.size();
            for (int j4 = 0; j4 < j; j4++) {
                double d9 = adouble[j4 * 4 + 3];
                if (!(d9 < 0.0)) {
                    double d11 = adouble[j4 * 4 + 0];
                    double d13 = adouble[j4 * 4 + 1];
                    double d15 = adouble[j4 * 4 + 2];
                    int k4 = Math.max(Mth.floor(d11 - d9), x);
                    int l = Math.max(Mth.floor(d13 - d9), y);
                    int i1 = Math.max(Mth.floor(d15 - d9), z);
                    int j1 = Math.max(Mth.floor(d11 + d9), k4);
                    int k1 = Math.max(Mth.floor(d13 + d9), l);
                    int l1 = Math.max(Mth.floor(d15 + d9), i1);

                    for (int i2 = k4; i2 <= j1; i2++) {
                        double d5 = ((double) i2 + 0.5 - d11) / d9;
                        if (d5 * d5 < 1.0) {
                            for (int j2 = l; j2 <= k1; j2++) {
                                double d6 = ((double) j2 + 0.5 - d13) / d9;
                                if (d5 * d5 + d6 * d6 < 1.0) {
                                    for (int k2 = i1; k2 <= l1; k2++) {
                                        double d7 = ((double) k2 + 0.5 - d15) / d9;
                                        if (d5 * d5 + d6 * d6 + d7 * d7 < 1.0 && !level.isOutsideBuildHeight(j2)) {
                                            int l2 = i2 - x + (j2 - y) * width + (k2 - z) * width * height;
                                            if (!bitset.get(l2)) {
                                                bitset.set(l2);
                                                blockpos$mutableblockpos.set(i2, j2, k2);
                                                if (level.ensureCanWrite(blockpos$mutableblockpos)) {
                                                    LevelChunkSection levelchunksection = scratch.getSection(blockpos$mutableblockpos);
                                                    if (levelchunksection != null) {
                                                        int i3 = SectionPos.sectionRelative(i2);
                                                        int j3 = SectionPos.sectionRelative(j2);
                                                        int k3 = SectionPos.sectionRelative(k2);
                                                        BlockState blockstate = levelchunksection.getBlockState(i3, j3, k3);
                                                        if (indexed) {
                                                            for (int t = 0; t < nt; t++) {
                                                                OreConfiguration.TargetBlockState target = targets.get(t);
                                                                if (OreFeature.canPlaceOre(blockstate, scratch, random, config, target, blockpos$mutableblockpos)) {
                                                                    levelchunksection.setBlockState(i3, j3, k3, target.state, false);
                                                                    i++;
                                                                    break;
                                                                }
                                                            }
                                                        } else {
                                                            for (OreConfiguration.TargetBlockState target : targets) {
                                                                if (OreFeature.canPlaceOre(blockstate, scratch, random, config, target, blockpos$mutableblockpos)) {
                                                                    levelchunksection.setBlockState(i3, j3, k3, target.state, false);
                                                                    i++;
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            scratch.close();
        }
        return i > 0;
    }
}
