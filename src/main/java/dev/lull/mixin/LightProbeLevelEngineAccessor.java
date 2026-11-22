package dev.lull.mixin;

import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Measurement only (-Dlab.light.probe / fixcheck): the level light engine's block engine (LightCheck.publishCheck). */
@Mixin(LevelLightEngine.class)
public interface LightProbeLevelEngineAccessor {
    @Accessor("blockEngine")
    LightEngine<?, ?> sleep$block();
}
