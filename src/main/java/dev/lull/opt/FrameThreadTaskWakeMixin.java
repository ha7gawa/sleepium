package dev.lull.opt;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FrameThread self-clocked frames (notes/75 stage 3): a task handed to Minecraft from another thread (a packet from
 * Netty, a loader's result) wakes the main thread from its wait for events (FrameThread.taskPosted) - it no longer
 * comes round once a frame on its own.
 */
@Mixin(BlockableEventLoop.class)
public abstract class FrameThreadTaskWakeMixin {
    @Inject(method = "tell(Ljava/lang/Runnable;)V", at = @At("TAIL"))
    private void sleep$wake(Runnable r, CallbackInfo ci) {
        if ((Object) this instanceof net.minecraft.client.Minecraft) dev.lull.optsupport.FrameThread.taskPosted();
    }
}
