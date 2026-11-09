package dev.lull.opt;

import java.util.Collection;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** exp/parallel-particles (client): a long particle list ticked by ParallelParticles (pure particles on the frame pool). */
@Mixin(ParticleEngine.class)
public abstract class ParallelParticlesMixin {
    @Inject(method = "tickParticleList", at = @At("HEAD"), cancellable = true)
    private void sleep$parallel(Collection<Particle> particles, CallbackInfo ci) {
        if (dev.lull.optsupport.ParallelParticles.tickList((ParticleEngine) (Object) this, particles)) ci.cancel();
    }
}
