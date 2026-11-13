package dev.lull.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
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
 * (vanilla's setEncryptionKey, Krypton's own method...). By PLAY encryption and compression are set up (both in LOGIN);
 * the server switches only after this client's channels arrived in PLAY. 1.20.1 has no configuration phase: PLAY frames
 * may already be on their way when the protocol changes - harmless after the splitter (it hands on whole frames).
 * Up to 1.2.2 the decoder stood before the splitter and was moved behind vanilla's cipher in setEncryptionKey - not
 * behind one installed another way (GitHub issue #7: Krypton, "VarInt too long").
 */
@Mixin(Connection.class)
public abstract class NetCoalesceClientMixin {
    @Shadow private Channel channel;
    @Shadow @Final private PacketFlow receiving;

    @Shadow public abstract boolean isMemoryConnection();

    @Inject(method = "setProtocol", at = @At("RETURN"))
    private void sleep$decoder(ConnectionProtocol protocol, CallbackInfo ci) {
        if (protocol != ConnectionProtocol.PLAY || this.receiving != PacketFlow.CLIENTBOUND || isMemoryConnection()) return;
        Channel ch = this.channel;
        if (ch == null) return;
        ch.eventLoop().execute(() -> {
            var p = ch.pipeline();
            if (p.get("splitter") != null && p.get("probe_uncoalesce") == null) p.addAfter("splitter", "probe_uncoalesce", new dev.lull.net.CoalesceDecoder());
        });
    }
}
