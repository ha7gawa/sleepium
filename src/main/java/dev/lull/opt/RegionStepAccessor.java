package dev.lull.opt;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SurfaceBiomeCells: which step a region serves (its biome table is only made for SURFACE). */
@Mixin(WorldGenRegion.class)
public interface RegionStepAccessor {
    @Accessor("generatingStep")
    ChunkStep sleep$step();
}
