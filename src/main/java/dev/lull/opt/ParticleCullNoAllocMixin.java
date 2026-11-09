package dev.lull.opt;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * exp/frame-garbage (client): ParticleEngine.render's frustum test of every particle, every frame, made a new AABB
 * (SingleQuadParticle.getRenderBoundingBox: the position +- the quad size) - a third of the render thread's allocation
 * with thousands of particles. For a particle class whose getRenderBoundingBox is SingleQuadParticle's (ParticleCull),
 * the same box goes straight into the frustum's cube test (the AABB constructor's min / max, its infinite check), and
 * the answer travels to the isVisible call as one of two marker boxes. Any other class: its own box, vanilla's test.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleCullNoAllocMixin {
    private static final String RENDER = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;getRenderBoundingBox(F)Lnet/minecraft/world/phys/AABB;"))
    private AABB sleep$box(Particle particle, float partialTicks, net.minecraft.client.renderer.LightTexture lt, net.minecraft.client.Camera camera, float pt,
                           Frustum frustum, java.util.function.Predicate<?> predicate) {
        if (frustum == null || !dev.lull.optsupport.ParticleCull.plain(particle)) return particle.getRenderBoundingBox(partialTicks);
        return dev.lull.optsupport.ParticleCull.visible((net.minecraft.client.particle.SingleQuadParticle) particle, partialTicks, frustum)
                ? dev.lull.optsupport.ParticleCull.SEEN : dev.lull.optsupport.ParticleCull.UNSEEN;
    }

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean sleep$visible(Frustum frustum, AABB box) {
        if (box == dev.lull.optsupport.ParticleCull.SEEN) return true;
        if (box == dev.lull.optsupport.ParticleCull.UNSEEN) return false;
        return frustum.isVisible(box);
    }
}
