package dev.lull.mixin;

import dev.lull.TickCensus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measurement only (-Dsleep.tickCensus=true): a server entity tick's unit, its spawns and effects (TickCensus). */
@Mixin(ServerLevel.class)
public abstract class TickCensusServerLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void sleep$censusStart(Entity e, CallbackInfo ci) {
        TickCensus.start(e);
    }

    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void sleep$censusEnd(Entity e, CallbackInfo ci) {
        TickCensus.end(e);
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void sleep$censusSpawn(Entity e, CallbackInfoReturnable<Boolean> cir) {
        TickCensus.spawn();
    }

    @Inject(method = {"playSeededSound", "levelEvent", "gameEvent", "broadcastEntityEvent"}, at = @At("HEAD"))
    private void sleep$censusEffect(CallbackInfo ci) {
        TickCensus.effect();
    }

    @Inject(method = "sendParticles", at = @At("HEAD"))
    private void sleep$censusParticles(CallbackInfoReturnable<?> cir) {
        TickCensus.effect();
    }
}
