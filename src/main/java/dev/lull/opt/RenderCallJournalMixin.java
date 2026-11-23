package dev.lull.opt;

import com.mojang.blaze3d.pipeline.RenderCall;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * ParallelRender: render-thread work a renderer defers from a worker job (RenderSystem.setShaderTexture and the other
 * "off the render thread: record it" calls) is kept in the job and run on the render thread when the job is merged, in
 * job order, instead of at the next frame's queue flush. Outside jobs the vanilla queue as before.
 */
@Mixin(RenderSystem.class)
public abstract class RenderCallJournalMixin {
    @Shadow @Final private static ConcurrentLinkedQueue<RenderCall> recordingQueue;

    /**
     * @author probe
     * @reason see the class comment; the vanilla body is the else branch
     */
    @Overwrite(remap = false)
    public static void recordRenderCall(RenderCall renderCall) {
        if (!dev.lull.optsupport.ParallelRender.journal(renderCall)) recordingQueue.add(dev.lull.optsupport.FrameThread.tracked(renderCall));
    }
}
