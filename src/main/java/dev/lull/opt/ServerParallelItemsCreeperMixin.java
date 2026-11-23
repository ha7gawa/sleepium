package dev.lull.opt;

import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast (not exact): a creeper that explodes in a run tick explodes at the run's end on the server
 * thread (the explosion damages entities and breaks blocks of other groups) - creepers tick in parallel like other mobs.
 */
@Mixin(Creeper.class)
public abstract class ServerParallelItemsCreeperMixin {
    @Shadow
    protected abstract void explodeCreeper();

    @Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
    private void sleep$fastExplode(CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.FAST || !dev.lull.optsupport.SpecTicks.inRun()) return;
        if (dev.lull.optsupport.SpecTicks.deferWorld(this::explodeCreeper)) ci.cancel();
    }
}
