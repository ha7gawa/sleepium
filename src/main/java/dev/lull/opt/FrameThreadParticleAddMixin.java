package dev.lull.opt;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FrameThread: a particle added while a frame draws (renderers that spawn particles: GeckoLib's keyframes, mods' entity
 * renderers) goes into ParticleEngine's queue of new particles from the frame thread while the main thread's particle
 * tick drains it - such particles wait in a concurrent queue and are added at the main thread's next particle tick.
 */
@Mixin(ParticleEngine.class)
public abstract class FrameThreadParticleAddMixin {
    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void sleep$fromFrame(Particle p, CallbackInfo ci) {
        if (dev.lull.optsupport.FrameThread.onFrameThread()) {
            dev.lull.optsupport.FrameThread.FRAME_PARTICLES.add(p);
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$addFromFrame(CallbackInfo ci) {
        Particle p;
        while ((p = dev.lull.optsupport.FrameThread.FRAME_PARTICLES.poll()) != null) ((ParticleEngine) (Object) this).add(p);
    }
}
