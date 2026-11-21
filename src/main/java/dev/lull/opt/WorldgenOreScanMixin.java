package dev.lull.opt;

import dev.lull.optsupport.OreScan;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * OreScan (place): vanilla's OreFeature.place, statement for statement, with its column loop answered by
 * OreScan.anyColumn (the same question in the same column order, each chunk fetched once; see there). The random
 * draws (nextFloat, two nextInt) and doPlace's arguments are vanilla's. The block scan of doPlace is in
 * WorldgenFeatureOreMixin under the same flag.
 */
@Mixin(OreFeature.class)
public abstract class WorldgenOreScanMixin {
    @Shadow
    protected abstract boolean doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config, double minX, double maxX,
                                       double minZ, double maxZ, double minY, double maxY, int x, int y, int z, int width, int height);

    /**
     * @author probe
     * @reason the column loop with each chunk fetched once (OreScan)
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<OreConfiguration> context) {
        RandomSource randomsource = context.random();
        BlockPos blockpos = context.origin();
        WorldGenLevel worldgenlevel = context.level();
        OreConfiguration oreconfiguration = context.config();
        float f = randomsource.nextFloat() * (float) Math.PI;
        float f1 = (float) oreconfiguration.size / 8.0F;
        int i = Mth.ceil(((float) oreconfiguration.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d0 = (double) blockpos.getX() + Math.sin((double) f) * (double) f1;
        double d1 = (double) blockpos.getX() - Math.sin((double) f) * (double) f1;
        double d2 = (double) blockpos.getZ() + Math.cos((double) f) * (double) f1;
        double d3 = (double) blockpos.getZ() - Math.cos((double) f) * (double) f1;
        double d4 = (double) (blockpos.getY() + randomsource.nextInt(3) - 2);
        double d5 = (double) (blockpos.getY() + randomsource.nextInt(3) - 2);
        int k = blockpos.getX() - Mth.ceil(f1) - i;
        int l = blockpos.getY() - 2 - i;
        int i1 = blockpos.getZ() - Mth.ceil(f1) - i;
        int j1 = 2 * (Mth.ceil(f1) + i);
        int k1 = 2 * (2 + i);
        if (OreScan.anyColumn(worldgenlevel, l, k, i1, j1))
            return this.doPlace(worldgenlevel, randomsource, oreconfiguration, d0, d1, d2, d3, d4, d5, k, l, i1, j1, k1);
        return false;
    }
}
