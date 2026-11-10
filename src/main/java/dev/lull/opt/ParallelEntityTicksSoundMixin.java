package dev.lull.opt;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** exp/parallel-entities: a sound played from the tail's worker is a conflict (its class leaves the tail). */
@Mixin(SoundManager.class)
public abstract class ParallelEntityTicksSoundMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void sleep$sound(SoundInstance s, CallbackInfo ci) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.conflict("a sound played");
    }
}
