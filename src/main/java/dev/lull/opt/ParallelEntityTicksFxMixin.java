package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/parallel-entities: a particle added from the tail's worker is a conflict (its class leaves the tail). Until it
 * leaves, its particles are made while the walk runs on other threads too (main helps the walk since 11-17): a particle
 * made and added under one lock while a walk runs - the providers draw from shared static sources (GlowParticle.RANDOM:
 * "Accessing LegacyRandomSource from multiple threads", 11-17 host zoo), the engine's queue is a plain deque.
 */
@Mixin(ParticleEngine.class)
public abstract class ParallelEntityTicksFxMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void sleep$particle(Particle p, CallbackInfo ci) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.conflict("a particle added");
    }

    @WrapMethod(method = "createParticle")
    private Particle sleep$createLocked(ParticleOptions o, double x, double y, double z, double dx, double dy, double dz, Operation<Particle> original) {
        if (!dev.lull.optsupport.ParticleWorkers.active) return original.call(o, x, y, z, dx, dy, dz);
        synchronized (dev.lull.optsupport.ParticleWorkers.FX_LOCK) {
            return original.call(o, x, y, z, dx, dy, dz);
        }
    }

    @WrapMethod(method = "add")
    private void sleep$addLocked(Particle p, Operation<Void> original) {
        if (!dev.lull.optsupport.ParticleWorkers.active) {
            original.call(p);
            return;
        }
        synchronized (dev.lull.optsupport.ParticleWorkers.FX_LOCK) {
            original.call(p);
        }
    }
}
