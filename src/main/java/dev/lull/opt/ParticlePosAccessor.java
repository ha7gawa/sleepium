package dev.lull.opt;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** ParticleCullNoAllocMixin: the particle's position for the render box test. */
@Mixin(Particle.class)
public abstract class ParticlePosAccessor implements dev.lull.optsupport.ParticleCull.ParticleAccess {
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;

    @Override
    public double sleep$x() {
        return x;
    }

    @Override
    public double sleep$y() {
        return y;
    }

    @Override
    public double sleep$z() {
        return z;
    }
}
