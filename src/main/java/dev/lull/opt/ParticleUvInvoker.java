package dev.lull.opt;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** ParticleGpu: a single-quad particle's sprite UVs (protected getters). */
@Mixin(SingleQuadParticle.class)
public abstract class ParticleUvInvoker implements dev.lull.optsupport.ParticleGpu.Uv {
    @Shadow protected abstract float getU0();
    @Shadow protected abstract float getU1();
    @Shadow protected abstract float getV0();
    @Shadow protected abstract float getV1();

    @Override public float sleep$u0() { return getU0(); }
    @Override public float sleep$u1() { return getU1(); }
    @Override public float sleep$v0() { return getV0(); }
    @Override public float sleep$v1() { return getV1(); }
}
