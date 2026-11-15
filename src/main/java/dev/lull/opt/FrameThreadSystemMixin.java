package dev.lull.opt;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.lull.optsupport.FrameThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.opt.FrameThreadMixin: polls stay on the main thread; the main thread's GL borrows the context (FrameThread). */
@Mixin(value = RenderSystem.class, remap = false)
public abstract class FrameThreadSystemMixin {
    @Inject(method = "pollEvents", at = @At("HEAD"), cancellable = true)
    private static void sleep$noPollHere(CallbackInfo ci) {
        if (FrameThread.onFrameThread()) ci.cancel();
    }

    @Inject(method = "assertOnRenderThread", at = @At("HEAD"))
    private static void sleep$borrow(CallbackInfo ci) {
        FrameThread.borrowIfNeeded();
    }

    @Inject(method = "assertOnRenderThreadOrInit", at = @At("HEAD"))
    private static void sleep$borrowInit(CallbackInfo ci) {
        FrameThread.borrowIfNeeded();
    }
}
