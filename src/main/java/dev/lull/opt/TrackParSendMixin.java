package dev.lull.opt;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** part of TrackPar (lab): a send made by the tracker's per-entity work is kept in that entity's buffer (sent in order after). */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class TrackParSendMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void sleep$trackParSend(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (dev.lull.optsupport.TrackPar.capturing && dev.lull.optsupport.TrackPar.capture(this, packet, listener)) ci.cancel();
    }
}
