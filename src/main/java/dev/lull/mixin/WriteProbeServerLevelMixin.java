package dev.lull.mixin;

import dev.lull.BeTickStats;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** New entities and sounds (see BeTickStats); block events and GameEvents are counted in EventSourcesMixin. */
@Mixin(ServerLevel.class)
public abstract class WriteProbeServerLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void sleep$addEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        BeTickStats.write();
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void sleep$soundAt(Player p, double x, double y, double z, Holder<SoundEvent> s, SoundSource c, float v, float pi, long seed, CallbackInfo ci) {
        BeTickStats.write();
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void sleep$soundEntity(Player p, Entity e, Holder<SoundEvent> s, SoundSource c, float v, float pi, long seed, CallbackInfo ci) {
        BeTickStats.write();
    }
}
