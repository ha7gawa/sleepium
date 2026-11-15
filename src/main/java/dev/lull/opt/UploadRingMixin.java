package dev.lull.opt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** UploadRing (-Dlab.gpu.uploadRing): a frame begins - the ring's next segment. */
@Mixin(Minecraft.class)
public abstract class UploadRingMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void sleep$ringFrame(boolean renderLevel, CallbackInfo ci) {
        if (!dev.lull.optsupport.FrameThread.active) dev.lull.optsupport.UploadRing.frameBegin();   // (the frame thread: at its job's start)
    }
}
