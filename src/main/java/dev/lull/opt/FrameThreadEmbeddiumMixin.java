package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.lull.optsupport.FrameThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FrameThread with Embeddium (1.20.1): its RenderSectionManager keeps the thread it was made on (the main thread) as
 * "the render thread"; scheduleRebuild from any other thread queues itself for runAsyncTasks - which the frame thread
 * runs, and from the frame thread the call queued itself again: one frame never ended (Biohazard, 474 mods, 11-17: the
 * frame thread spun in runAsyncTasks, the main thread waited for a ring slot). On the frame thread the check sees the
 * frame thread as the render thread (it draws the frames); calls from the main thread still go through Embeddium's
 * queue, as they did when they came from other threads.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class FrameThreadEmbeddiumMixin {
    @ModifyExpressionValue(method = "scheduleRebuild", require = 0,
            at = @At(value = "FIELD", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager;renderThread:Ljava/lang/Thread;"))
    private Thread sleep$frameThreadRenders(Thread renderThread) {
        return FrameThread.onFrameThread() ? Thread.currentThread() : renderThread;
    }
}
