package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WorldgenFeatureAlloc (placement): the spreads for PlacementWalk. */
@Mixin(RandomOffsetPlacement.class)
public interface RandomOffsetPlacementAccessor extends PlacementWalk.RandomOffset {
    @Override
    @Accessor("xzSpread")
    IntProvider sleep$xzSpread();

    @Override
    @Accessor("ySpread")
    IntProvider sleep$ySpread();
}
