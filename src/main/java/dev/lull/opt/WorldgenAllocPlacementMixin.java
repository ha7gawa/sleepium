package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * WorldgenAlloc (placement): PlacedFeature.placeWithContext chains one flatMap per placement modifier over
 * Stream.of(origin) — a pipeline of stream objects per placed feature per chunk. A sequential flatMap calls the mapper
 * for an element and pushes each element of the returned stream downstream before taking the next one (depth first),
 * closing the returned stream afterwards. The same traversal written as recursion (PlacementWalk): the same
 * getPositions work, the same random draws, the same place calls, in the same order; vanilla's own modifiers produce
 * their positions without streams, any other modifier's stream is still consumed and closed.
 * (11-21: the first version's forEach lambda per modifier and element was itself ~4.6 % of the workers' allocation.)
 */
@Mixin(PlacedFeature.class)
public abstract class WorldgenAllocPlacementMixin {
    @Shadow @Final private Holder<ConfiguredFeature<?, ?>> feature;
    @Shadow @Final private List<PlacementModifier> placement;

    /**
     * @author probe
     * @reason flatMap chain replaced by the equivalent depth-first walk
     */
    @Overwrite
    private boolean placeWithContext(PlacementContext context, RandomSource source, BlockPos pos) {
        return PlacementWalk.walk(this.placement, this.feature.value(), context, source, pos);
    }
}
