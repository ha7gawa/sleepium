package dev.lull.mixin;

import dev.lull.LightProbe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dlab.light.probe=true): sky sources of a chunk (the columns set to 15, the edges queued) and the fill on enable. */
@Mixin(SkyLightEngine.class)
public abstract class LightProbeSkyMixin {
    @Unique private long sleep$src, sleep$en;

    @Inject(method = "propagateLightSources", at = @At("HEAD"))
    private void sleep$head(ChunkPos pos, CallbackInfo ci) {
        sleep$src = System.nanoTime();
    }

    @Inject(method = "propagateLightSources", at = @At("RETURN"))
    private void sleep$ret(ChunkPos pos, CallbackInfo ci) {
        LightProbe.eng(true, 7, System.nanoTime() - sleep$src);
        LightProbe.eng(true, 8, 1);
    }

    @Inject(method = "setLightEnabled", at = @At("HEAD"))
    private void sleep$enHead(ChunkPos pos, boolean on, CallbackInfo ci) {
        sleep$en = System.nanoTime();
    }

    @Inject(method = "setLightEnabled", at = @At("RETURN"))
    private void sleep$enRet(ChunkPos pos, boolean on, CallbackInfo ci) {
        LightProbe.eng(true, 9, System.nanoTime() - sleep$en);
    }
}
