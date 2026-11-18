package dev.lull.opt;

import dev.lull.optsupport.BeCensus;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BeCensus (measuring): the server level's sounds, particles and game events reached inside a block-entity tick. */
@Mixin(ServerLevel.class)
public abstract class BeCensusEffectsMixin {
    @Inject(method = {"playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"},
            at = @At("HEAD"), require = 0)
    private void sleep$sound(CallbackInfo ci) {
        BeCensus.mark(BeCensus.FX);
    }

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("HEAD"), require = 0)
    private void sleep$particles(CallbackInfoReturnable<Integer> cir) {
        BeCensus.mark(BeCensus.FX);
    }

    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V", at = @At("HEAD"), require = 0)
    private void sleep$gameEvent(CallbackInfo ci) {
        BeCensus.mark(BeCensus.FX);
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), require = 0)
    private void sleep$event(CallbackInfo ci) {
        BeCensus.mark(BeCensus.FX);
    }
}
