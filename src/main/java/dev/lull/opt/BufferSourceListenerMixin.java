package dev.lull.opt;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RenderCacheMixin (issue #11): a mod's event listener inside a render cache recording asking the frame's own buffer
 * source for a buffer (health bars, name tags drawn past the source they are given) is answered by the recording -
 * recorded with the rest, the cache kept; a type the recording cannot hold marks it unsupported (drawn the vanilla
 * way). Only inside listeners: Sleepium's own code around them keeps the frame's source.
 */
@Mixin(value = MultiBufferSource.BufferSource.class, priority = 500)   // (before EntityBatchMixin's getBuffer: a listener's request is the recording's, not a batch's)
public abstract class BufferSourceListenerMixin {
    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void sleep$listener(RenderType type, CallbackInfoReturnable<VertexConsumer> cir) {
        if (dev.lull.optsupport.ListenerScope.depth == 0 || dev.lull.optsupport.ListenerScope.recording != Thread.currentThread()) return;
        VertexConsumer v = dev.lull.optsupport.RenderCache.listenerBuffer((MultiBufferSource.BufferSource) (Object) this, type);
        if (v != null) cir.setReturnValue(v);
    }
}
