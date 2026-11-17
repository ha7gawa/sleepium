package dev.lull.opt;

import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dlab.meshMemo: a light update batch (the light a compile reads is the engine's updating copy, the hash reads the
 * visible one) - counted before and after, so a compile that ran across one keeps no memo (MeshMemo.LIGHT_EPOCH).
 */
@Mixin(LevelLightEngine.class)
public abstract class MeshMemoLightMixin {
    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void sleep$lightBefore(CallbackInfoReturnable<Integer> cir) {
        dev.lull.optsupport.MeshMemo.LIGHT_EPOCH.incrementAndGet();
    }

    @Inject(method = "runLightUpdates", at = @At("RETURN"))
    private void sleep$lightAfter(CallbackInfoReturnable<Integer> cir) {
        dev.lull.optsupport.MeshMemo.LIGHT_EPOCH.incrementAndGet();
    }
}
