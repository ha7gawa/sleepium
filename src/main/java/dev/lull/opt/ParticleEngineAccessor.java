package dev.lull.opt;

import java.util.Map;
import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ParticleShapeMemoMixin's measurement: the live particles per render type. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, Queue<Particle>> sleep$particles();
}
