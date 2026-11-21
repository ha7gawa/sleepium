package dev.lull.opt;

import dev.lull.optsupport.BiomeFilterFast;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** BiomeFilterFast: the answer from the region's 3 x 3 biome set when all its biomes agree (see BiomeFilterFast). */
@Mixin(BiomeFilter.class)
public abstract class BiomeFilterFastMixin {
    /**
     * @author probe
     * @reason the answer without getBiome when every biome getBiome can return agrees (BiomeFilterFast); otherwise vanilla
     */
    @Overwrite
    protected boolean shouldPlace(PlacementContext p_226317_, RandomSource p_226318_, BlockPos p_226319_) {
        PlacedFeature placedfeature = p_226317_.topFeature()
            .orElseThrow(() -> new IllegalStateException("Tried to biome check an unregistered feature, or a feature that should not restrict the biome"));
        if (p_226317_.getLevel().getClass() == WorldGenRegion.class) {
            int a = BiomeFilterFast.decide((WorldGenRegion) p_226317_.getLevel(), p_226317_.generator(), placedfeature, p_226319_);
            if (a >= 0) {
                if (BiomeFilterFast.VERIFY) {
                    BiomeFilterFast.count(true);
                    Holder<Biome> holder = p_226317_.getLevel().getBiome(p_226319_);
                    BiomeFilterFast.check(a == 1, p_226317_.generator().getBiomeGenerationSettings(holder).hasFeature(placedfeature), placedfeature, p_226319_);
                }
                return a == 1;
            }
            if (BiomeFilterFast.VERIFY) BiomeFilterFast.count(false);
        }
        Holder<Biome> holder = p_226317_.getLevel().getBiome(p_226319_);
        return p_226317_.generator().getBiomeGenerationSettings(holder).hasFeature(placedfeature);
    }
}
