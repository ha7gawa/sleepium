package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WorldgenFeatureAlloc (placement): the scan's settings for PlacementWalk. */
@Mixin(EnvironmentScanPlacement.class)
public interface EnvironmentScanPlacementAccessor extends PlacementWalk.EnvironmentScan {
    @Override
    @Accessor("directionOfSearch")
    Direction sleep$directionOfSearch();

    @Override
    @Accessor("targetCondition")
    BlockPredicate sleep$targetCondition();

    @Override
    @Accessor("allowedSearchCondition")
    BlockPredicate sleep$allowedSearchCondition();

    @Override
    @Accessor("maxSteps")
    int sleep$maxSteps();
}
