package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** WorldgenFeatureAlloc (placement): a filter's shouldPlace for PlacementWalk. */
@Mixin(PlacementFilter.class)
public interface PlacementFilterInvoker extends PlacementWalk.Filter {
    @Override
    @Invoker("shouldPlace")
    boolean sleep$shouldPlace(PlacementContext context, RandomSource random, BlockPos pos);
}
