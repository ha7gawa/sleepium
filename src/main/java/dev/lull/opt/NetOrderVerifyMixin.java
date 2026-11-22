package dev.lull.opt;

import dev.lull.optsupport.NetOrderVerify;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only (-Dlab.opt.NetOrderVerify=true, with NetReactor): the packets' order per connection and sending thread
 * at sendPacket (NetReactor's overwrite: injectors apply after every mixin's methods are merged) against the order netty
 * writes them in (doSendPacket). See optsupport/NetOrderVerify.
 */
@Mixin(Connection.class)
public abstract class NetOrderVerifyMixin {
    @Unique private final NetOrderVerify.State sleep$order = new NetOrderVerify.State();

    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void sleep$orderIn(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        this.sleep$order.in(packet);
    }

    @Inject(method = "doSendPacket", at = @At("HEAD"))
    private void sleep$orderOut(Packet<?> packet, @Nullable PacketSendListener listener, ConnectionProtocol newProtocol, ConnectionProtocol current, CallbackInfo ci) {
        this.sleep$order.out(packet);
    }
}
