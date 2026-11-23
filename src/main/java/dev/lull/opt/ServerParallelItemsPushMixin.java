package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast (not exact): a push between two mobs reads and writes both - with the other one ticking on
 * another thread its lazy caches (the block it is in) can be reset under the read (a NullPointerException in
 * isLivingOnLadder, 11-23). The push is kept for the run's end, in walk order, on the server thread; it moves them from
 * the next tick on, as a push in the tick would.
 */
@Mixin(LivingEntity.class)
public abstract class ServerParallelItemsPushMixin {
    @Shadow
    protected abstract void doPush(Entity other);

    @Inject(method = "doPush", at = @At("HEAD"), cancellable = true)
    private void sleep$fastPush(Entity other, CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.FAST) return;
        if (dev.lull.optsupport.SpecTicks.deferWorld(() -> doPush(other))) ci.cancel();
    }
}
