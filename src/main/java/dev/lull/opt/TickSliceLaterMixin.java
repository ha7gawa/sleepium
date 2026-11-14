package dev.lull.opt;

import dev.lull.optsupport.TickSlice;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** TickSlice: particles are ticked after the entities - in a frame in the middle of a tick they are drawn where they are. */
@Mixin(ParticleEngine.class)
public abstract class TickSliceLaterMixin {
    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)   // (Forge's method: no SRG name)
    private float sleep$partial(float pt) {
        return TickSlice.partialLater(pt);
    }
}
