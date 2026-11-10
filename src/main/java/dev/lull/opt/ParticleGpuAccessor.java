package dev.lull.opt;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** ParticleGpu: the particle's position, roll, colour and light. */
@Mixin(Particle.class)
public abstract class ParticleGpuAccessor implements dev.lull.optsupport.ParticleGpu.Access {
    @Shadow protected double xo;
    @Shadow protected double yo;
    @Shadow protected double zo;
    @Shadow protected double x;
    @Shadow protected double y;
    @Shadow protected double z;
    @Shadow protected float roll;
    @Shadow protected float oRoll;
    @Shadow protected float rCol;
    @Shadow protected float gCol;
    @Shadow protected float bCol;
    @Shadow protected float alpha;
    @Shadow @org.spongepowered.asm.mixin.Final protected net.minecraft.client.multiplayer.ClientLevel level;

    @Shadow
    protected abstract int getLightColor(float partialTick);

    @Override public double sleep$xo() { return xo; }
    @Override public double sleep$yo() { return yo; }
    @Override public double sleep$zo() { return zo; }
    @Override public double sleep$px() { return x; }
    @Override public double sleep$py() { return y; }
    @Override public double sleep$pz() { return z; }
    @Override public float sleep$roll() { return roll; }
    @Override public float sleep$oRoll() { return oRoll; }
    @Override public float sleep$r() { return rCol; }
    @Override public float sleep$g() { return gCol; }
    @Override public float sleep$b() { return bCol; }
    @Override public float sleep$a() { return alpha; }
    @Override public int sleep$light(float partialTicks) { return getLightColor(partialTicks); }
    @Override public net.minecraft.client.multiplayer.ClientLevel sleep$level() { return level; }
}
