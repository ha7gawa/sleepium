package dev.lull.mixin;

import dev.lull.LightProbe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dlab.light.probe=true): block light sources of a chunk (the emitting blocks found and queued). */
@Mixin(BlockLightEngine.class)
public abstract class LightProbeBlockMixin {
    @Unique private long sleep$src;

    @Inject(method = "propagateLightSources", at = @At("HEAD"))
    private void sleep$head(ChunkPos pos, CallbackInfo ci) {
        sleep$src = System.nanoTime();
    }

    @Inject(method = "propagateLightSources", at = @At("RETURN"))
    private void sleep$ret(ChunkPos pos, CallbackInfo ci) {
        LightProbe.eng(false, 7, System.nanoTime() - sleep$src);
        LightProbe.eng(false, 8, 1);
    }
}
