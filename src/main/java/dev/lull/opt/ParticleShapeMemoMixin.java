package dev.lull.opt;

import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** exp/particle-shapes (client): the particle tick's window for CollideKernel's per-position shape memo (once per tick). */
@Mixin(ParticleEngine.class)
public abstract class ParticleShapeMemoMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$start(CallbackInfo ci) {
        dev.lull.optsupport.CollideKernel.particleTick(true);
        dev.lull.optsupport.ParticleStats.start();
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V", at = @At("HEAD"), remap = false)
    private void sleep$renderStart(CallbackInfo ci) {
        dev.lull.optsupport.ParticleStats.renderStart();
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V", at = @At("RETURN"), remap = false)
    private void sleep$renderEnd(CallbackInfo ci) {
        dev.lull.optsupport.ParticleStats.renderEnd();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$end(CallbackInfo ci) {
        dev.lull.optsupport.CollideKernel.particleTick(false);
        dev.lull.optsupport.ParticleStats.end((ParticleEngine) (Object) this);
    }
}
