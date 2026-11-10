package dev.lull.mixin;

import dev.lull.EventSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counters for vanilla's in-tick event mechanisms on the server (see {@link EventSources}). */
@Mixin(ServerLevel.class)
public abstract class EventSourcesMixin {
    @Inject(method = "blockEvent", at = @At("HEAD"))
    private void sleep$blockEvent(BlockPos pos, Block block, int id, int param, CallbackInfo ci) {
        EventSources.blockEvents++;
        dev.lull.BeTickStats.write();
    }

    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V", at = @At("HEAD"))
    private void sleep$gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context ctx, CallbackInfo ci) {
        EventSources.gameEvents++;
        dev.lull.BeTickStats.write();
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void sleep$entityTick(Entity entity, CallbackInfo ci) {
        EventSources.entityTicks++;
    }
}
