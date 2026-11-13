package dev.lull.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Network engine stage C, the client's half: a client connection entering PLAY (not a memory one) gets the container
 * decoder right after its frame splitter. It passes frames through until the server switches (only a server with the
 * engine on, after seeing this client's capability channel - in PLAY), so it costs a length check per frame.
 * <p>
 * After the splitter, the decoder reads whole decrypted frames whoever installed the cipher in front of the splitter
 * (vanilla's setEncryptionKey, Krypton's own setupEncryption...). By PLAY, encryption and compression are set up (both
 * in LOGIN) and the server has not switched yet; the inbound PLAY protocol is set before the client acknowledges the
 * configuration's end, so no container can come before it. Up to 1.2.2 the decoder stood before the splitter and was
 * moved behind vanilla's cipher in setEncryptionKey - not behind Krypton's (GitHub issue #7: "VarInt too long").
 */
@Mixin(Connection.class)
public abstract class NetCoalesceClientMixin {
    @Shadow private Channel channel;

    @Shadow public abstract boolean isMemoryConnection();

    @Inject(method = "setupInboundProtocol", at = @At("RETURN"))
    private void sleep$decoder(ProtocolInfo<?> info, PacketListener listener, CallbackInfo ci) {
        if (info.id() != ConnectionProtocol.PLAY || info.flow() != PacketFlow.CLIENTBOUND || isMemoryConnection()) return;
        Channel ch = this.channel;
        if (ch == null) return;
        ch.eventLoop().execute(() -> {
            var p = ch.pipeline();
            if (p.get("splitter") != null && p.get("probe_uncoalesce") == null) p.addAfter("splitter", "probe_uncoalesce", new dev.lull.net.CoalesceDecoder());
        });
    }
}
