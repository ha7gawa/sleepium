package dev.lull.opt;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TintBlend's zoom: the biome manager's seed and source (its fuzzy zoom worked out once per corner for a layer). */
@Mixin(BiomeManager.class)
public interface TintBlendBiomeAccessor {
    @Accessor("biomeZoomSeed")
    long sleep$zoomSeed();

    @Accessor("noiseBiomeSource")
    BiomeManager.NoiseBiomeSource sleep$source();
}
