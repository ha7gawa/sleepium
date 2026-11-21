package dev.lull.opt;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SurfaceBiomeCells: which status a region generates (1.20.1: WorldGenRegion.generatingStatus) (its biome table is only made for SURFACE). */
@Mixin(WorldGenRegion.class)
public interface RegionStepAccessor {
    @Accessor("generatingStatus")
    ChunkStatus sleep$status();
}
