package dev.lull.opt;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of ServerParallelItems: what a run item's tick must not do (a spawn, an effect) - a trap if it does. */
@Mixin(ServerLevel.class)
public abstract class ServerParallelItemsTrapMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void sleep$spiSpawn(net.minecraft.world.entity.Entity e, CallbackInfoReturnable<Boolean> cir) {
        if (dev.lull.optsupport.ServerParallelItems.running()) dev.lull.optsupport.ServerParallelItems.trap("spawn " + e.getType());
    }

    @Inject(method = {"playSeededSound", "levelEvent", "gameEvent", "broadcastEntityEvent"}, at = @At("HEAD"))
    private void sleep$spiEffect(CallbackInfo ci) {
        if (dev.lull.optsupport.ServerParallelItems.running()) dev.lull.optsupport.ServerParallelItems.trap("effect / world write");
    }

    @Inject(method = "sendParticles", at = @At("HEAD"))
    private void sleep$spiParticles(CallbackInfoReturnable<?> cir) {
        if (dev.lull.optsupport.ServerParallelItems.running()) dev.lull.optsupport.ServerParallelItems.trap("particles");
    }
}
