package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): the Connection layer of a sampled send. */
@Mixin(net.minecraft.network.Connection.class)
public abstract class PipeScoutConnMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"))
    private void sleep$c0(net.minecraft.network.protocol.Packet<?> p, net.minecraft.network.PacketSendListener l, boolean flush, CallbackInfo ci) { PipeScout.connSendStart(); }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("RETURN"))
    private void sleep$c1(net.minecraft.network.protocol.Packet<?> p, net.minecraft.network.PacketSendListener l, boolean flush, CallbackInfo ci) { PipeScout.connSendEnd(); }
}
