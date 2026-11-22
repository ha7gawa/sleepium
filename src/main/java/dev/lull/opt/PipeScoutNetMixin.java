package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): the server thread's sends. */
@Mixin(net.minecraft.server.network.ServerCommonPacketListenerImpl.class)
public abstract class PipeScoutNetMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void sleep$n0(net.minecraft.network.protocol.Packet<?> p, net.minecraft.network.PacketSendListener l, CallbackInfo ci) { PipeScout.netStart(); }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("RETURN"))
    private void sleep$n1(net.minecraft.network.protocol.Packet<?> p, net.minecraft.network.PacketSendListener l, CallbackInfo ci) { PipeScout.netEnd(); }
}
