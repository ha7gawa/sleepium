package testmods.ticksplit;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import testmods.meter.EntityCensus;

/** Test only (-Dsleep.meter.tickSplit=true): particles added, for EntityCensus. */
@Mixin(ParticleEngine.class)
public abstract class CensusFxMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void sleep$particle(Particle p, CallbackInfo ci) {
        EntityCensus.particles++;
    }
}
