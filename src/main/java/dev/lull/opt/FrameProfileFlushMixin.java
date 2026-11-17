package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameProfile;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;

/** FrameProfile (measuring): a buffer source's batches ended and drawn - a "flush" section where they are (wrapped whole). */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class FrameProfileFlushMixin {
    @WrapMethod(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;)V")
    private void sleep$flush(RenderType type, Operation<Void> original) {
        if (!FrameThread.drawingHere()) {
            original.call(type);
            return;
        }
        FrameProfile.INSTANCE.push("flush");
        try {
            original.call(type);
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }

    @WrapMethod(method = "endLastBatch")
    private void sleep$flushLast(Operation<Void> original) {
        if (!FrameThread.drawingHere()) {
            original.call();
            return;
        }
        FrameProfile.INSTANCE.push("flush_last");
        try {
            original.call();
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }
}
