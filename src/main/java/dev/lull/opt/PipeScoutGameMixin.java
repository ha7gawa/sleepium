package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): a player's connection tick, its movement packets handled. */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class PipeScoutGameMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$t0(CallbackInfo ci) { PipeScout.connStart(); }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$t1(CallbackInfo ci) { PipeScout.connEnd(); }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void sleep$m0(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket p, CallbackInfo ci) { PipeScout.moveStart(); }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void sleep$m1(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket p, CallbackInfo ci) { PipeScout.moveEnd(); }
}
