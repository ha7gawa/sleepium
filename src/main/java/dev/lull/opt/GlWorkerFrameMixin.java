package dev.lull.opt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.glWorker: the GL worker's job (the animated textures' tick) waited for on the GPU before the frame's draws. */
@Mixin(Minecraft.class)
public abstract class GlWorkerFrameMixin {
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void sleep$glWorkerJoin(boolean renderLevel, CallbackInfo ci) {
        dev.lull.optsupport.GlWorker.joinIfBusy();
    }
}
