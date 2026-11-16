package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FrameThread.pumpMotion: while the main thread polls the window in the middle of a long tick, Minecraft.execute queues
 * what the input callbacks hand it (keys, clicks, scrolling, the mouse's moves) instead of running it there - the queued
 * tasks run at the next runTick's start, before its tick, as packets do. Only during that poll, on the main thread.
 */
@Mixin(ReentrantBlockableEventLoop.class)
public abstract class FrameThreadPumpQueueMixin {
    @ModifyReturnValue(method = "scheduleExecutables", at = @At("RETURN"))
    private boolean sleep$queueWhilePumping(boolean schedule) {
        return schedule || dev.lull.optsupport.FrameThread.pumping;
    }
}
