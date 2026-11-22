package dev.lull.mixin;

import dev.lull.LightProbe;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measurement only (-Dlab.light.probe=true): one engine's (block / sky) runLightUpdates by pass. */
@Mixin(LightEngine.class)
public abstract class LightProbeEngineMixin {
    @Shadow @Final private LongOpenHashSet blockNodesToCheck;
    @Unique private long sleep$run, sleep$pass, sleep$pub, sleep$en;

    @Unique
    private boolean sleep$sky() {
        return (Object) this instanceof SkyLightEngine;
    }

    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void sleep$head(CallbackInfoReturnable<Integer> cir) {
        LightProbe.eng(sleep$sky(), 1, blockNodesToCheck.size());
        sleep$run = System.nanoTime();
    }

    @Inject(method = "runLightUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;markNewInconsistencies(Lnet/minecraft/world/level/lighting/LightEngine;)V"))
    private void sleep$publish(CallbackInfoReturnable<Integer> cir) {
        sleep$pub = System.nanoTime();
    }

    @Inject(method = "runLightUpdates", at = @At("RETURN"))
    private void sleep$ret(CallbackInfoReturnable<Integer> cir) {
        long t = System.nanoTime();
        boolean sky = sleep$sky();
        LightProbe.eng(sky, 0, t - sleep$run);
        LightProbe.eng(sky, 6, t - sleep$pub);
    }

    @Inject(method = "propagateDecreases", at = @At("HEAD"))
    private void sleep$decHead(CallbackInfoReturnable<Integer> cir) {
        sleep$pass = System.nanoTime();
    }

    @Inject(method = "propagateDecreases", at = @At("RETURN"))
    private void sleep$decRet(CallbackInfoReturnable<Integer> cir) {
        boolean sky = sleep$sky();
        LightProbe.eng(sky, 2, System.nanoTime() - sleep$pass);
        LightProbe.eng(sky, 3, cir.getReturnValue());
    }

    @Inject(method = "propagateIncreases", at = @At("HEAD"))
    private void sleep$incHead(CallbackInfoReturnable<Integer> cir) {
        sleep$pass = System.nanoTime();
    }

    @Inject(method = "propagateIncreases", at = @At("RETURN"))
    private void sleep$incRet(CallbackInfoReturnable<Integer> cir) {
        boolean sky = sleep$sky();
        LightProbe.eng(sky, 4, System.nanoTime() - sleep$pass);
        LightProbe.eng(sky, 5, cir.getReturnValue());
    }

    @Inject(method = "setLightEnabled", at = @At("HEAD"))
    private void sleep$enHead(ChunkPos pos, boolean on, CallbackInfo ci) {
        if (!sleep$sky()) sleep$en = System.nanoTime();
    }

    @Inject(method = "setLightEnabled", at = @At("RETURN"))
    private void sleep$enRet(ChunkPos pos, boolean on, CallbackInfo ci) {
        if (!sleep$sky()) LightProbe.eng(false, 9, System.nanoTime() - sleep$en);
    }
}
