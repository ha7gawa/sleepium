package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WorldgenFeatureAlloc (placement): the height provider for PlacementWalk. */
@Mixin(HeightRangePlacement.class)
public interface HeightRangePlacementAccessor extends PlacementWalk.HeightRange {
    @Override
    @Accessor("height")
    HeightProvider sleep$height();
}
