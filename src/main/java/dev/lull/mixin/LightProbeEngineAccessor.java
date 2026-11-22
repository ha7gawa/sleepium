package dev.lull.mixin;

import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Measurement only (-Dlab.light.probe): a light engine's storage and chunk getter (LightProbeCheckNodeMixin). */
@Mixin(LightEngine.class)
public interface LightProbeEngineAccessor {
    @Accessor("storage")
    LayerLightSectionStorage<?> sleep$storage();

    @Accessor("chunkSource")
    LightChunkGetter sleep$chunks();
}
