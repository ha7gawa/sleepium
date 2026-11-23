package dev.lull.opt;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FrameCoop: between the main thread's queued tasks (packets, mods' Minecraft.execute work) a safe point for a frame. */
@Mixin(BlockableEventLoop.class)
public abstract class FrameThreadTaskSafepointMixin {
    @Inject(method = "doRunTask", at = @At("HEAD"))
    private void sleep$coopSafepoint(Runnable task, CallbackInfo ci) {
        if (dev.lull.optsupport.FrameCoop.on()) dev.lull.optsupport.FrameCoop.safepoint();
    }
}
