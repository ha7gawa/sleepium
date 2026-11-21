package dev.lull.opt;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SurfaceBiomeCells: the biome manager's source (the region during generation: its stored biomes). */
@Mixin(BiomeManager.class)
public interface BiomeManagerSourceAccessor {
    @Accessor("noiseBiomeSource")
    BiomeManager.NoiseBiomeSource sleep$noiseSource();
}
