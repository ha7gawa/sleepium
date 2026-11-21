package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WorldgenFeatureAlloc (placement): the heightmap type for PlacementWalk. */
@Mixin(HeightmapPlacement.class)
public interface HeightmapPlacementAccessor extends PlacementWalk.Heightmap_ {
    @Override
    @Accessor("heightmap")
    Heightmap.Types sleep$heightmap();
}
