package dev.lull.opt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.ReputationEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast (with the POI lock): a reputation event writes into another villager's gossips (a murdered
 * villager's witnesses, ticking on other threads) - kept for the run's end on the server thread.
 */
@Mixin(ServerLevel.class)
public abstract class ServerParallelItemsReputationMixin {
    @Inject(method = "onReputationEvent", at = @At("HEAD"), cancellable = true)
    private void sleep$fastReputation(ReputationEventType type, Entity target, ReputationEventHandler host, CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.POI_LOCK || !dev.lull.optsupport.SpecTicks.inRun()) return;
        ServerLevel self = (ServerLevel) (Object) this;
        if (dev.lull.optsupport.SpecTicks.deferWorld(() -> self.onReputationEvent(type, target, host))) ci.cancel();
    }
}
