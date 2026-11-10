package dev.lull.opt;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** exp/parallel-entities: a particle added from the tail's worker is a conflict (its class leaves the tail). */
@Mixin(ParticleEngine.class)
public abstract class ParallelEntityTicksFxMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void sleep$particle(Particle p, CallbackInfo ci) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.conflict("a particle added");
    }
}
