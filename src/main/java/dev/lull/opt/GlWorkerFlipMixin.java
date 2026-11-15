package dev.lull.opt;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.glWorker: a fence after the frame's draws, before the swap sends them (the GL worker's next job waits for it). */
@Mixin(RenderSystem.class)
public abstract class GlWorkerFlipMixin {
    @Inject(method = "flipFrame", at = @At("HEAD"), remap = false)
    private static void sleep$glWorkerFrame(long window, CallbackInfo ci) {
        dev.lull.optsupport.GlWorker.frameEnd();
    }
}
