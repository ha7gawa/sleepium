package dev.lull.opt;

import dev.lull.optsupport.TickSlice;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** TickSlice: particles are ticked after the entities - in a frame in the middle of a tick they are drawn where they are. */
@Mixin(ParticleEngine.class)
public abstract class TickSliceLaterMixin {
    @ModifyVariable(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float sleep$partial(float pt) {
        return TickSlice.partialLater(pt);
    }
}
