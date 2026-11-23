package dev.lull.opt;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ParallelEntitiesMixin (issue #11): a render worker's job asking the frame's own buffer source for a buffer - a mod's
 * render event listener or renderer drawing into the game's buffers instead of the ones it is given - would write
 * into the render thread's builders at the same time as it (broken vertices: stray textures, dark frames, "Not filled
 * all elements of the vertex"). The job is refused before it writes anything: it goes to the render thread, its type
 * stays there.
 */
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class ParallelEntitiesFrameBufferGuardMixin {
    @Inject(method = "getBuffer", at = @At("HEAD"))
    private void sleep$guard(RenderType type, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!dev.lull.optsupport.ParallelRender.inJob() || com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
        var rb = Minecraft.getInstance().renderBuffers();
        Object self = this;
        if (self == rb.bufferSource() || self == rb.crumblingBufferSource()) throw dev.lull.optsupport.ParallelRender.REFUSE_FRAME_BUFFER;
    }
}
