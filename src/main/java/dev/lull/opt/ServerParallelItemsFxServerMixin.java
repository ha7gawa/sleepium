package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** part of SpecTicks (lab): a run tick's effects on the server level, kept for after the run in its order. */
@Mixin(ServerLevel.class)
public abstract class ServerParallelItemsFxServerMixin {
    @WrapMethod(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V")
    private void sleep$specSeeded(Player p, double x, double y, double z, Holder<SoundEvent> s, SoundSource src, float v, float pitch, long seed, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(p, x, y, z, s, src, v, pitch, seed))) original.call(p, x, y, z, s, src, v, pitch, seed);
    }

    @WrapMethod(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V")
    private void sleep$specSeededEntity(Player p, Entity e, Holder<SoundEvent> s, SoundSource src, float v, float pitch, long seed, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(p, e, s, src, v, pitch, seed))) original.call(p, e, s, src, v, pitch, seed);
    }

    @WrapMethod(method = "levelEvent")
    private void sleep$specLevelEvent(Player p, int type, BlockPos pos, int data, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(p, type, pos, data))) original.call(p, type, pos, data);
    }

    @WrapMethod(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V")
    private void sleep$specGameEvent(Holder<GameEvent> ev, Vec3 pos, GameEvent.Context ctx, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(ev, pos, ctx))) original.call(ev, pos, ctx);
    }

    @WrapMethod(method = "broadcastEntityEvent")
    private void sleep$specEntityEvent(Entity e, byte state, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(e, state))) original.call(e, state);
    }

    @WrapMethod(method = "broadcastDamageEvent")
    private void sleep$specDamageEvent(Entity e, DamageSource src, Operation<Void> original) {
        if (!dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(e, src))) original.call(e, src);
    }

    @WrapMethod(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I")
    private <T extends ParticleOptions> int sleep$specParticles(T type, double x, double y, double z, int count, double dx, double dy, double dz, double speed, Operation<Integer> original) {
        if (dev.lull.optsupport.SpecTicks.running()) {
            // (the count of players it was sent to is returned: a run tick that reads it is not supported - none of vanilla's mobs does)
            if (dev.lull.optsupport.SpecTicks.deferFx(() -> original.call(type, x, y, z, count, dx, dy, dz, speed))) return 0;
        }
        return original.call(type, x, y, z, count, dx, dy, dz, speed);
    }
}
