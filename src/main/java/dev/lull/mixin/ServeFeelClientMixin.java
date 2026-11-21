package dev.lull.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ServeFeel (notes/92, -Dsleep.serveFeel=true, a client joined to a server): the moment each answer reaches the client -
 * the handlers' first call, on the network thread (the second, on the main thread, is ignored).
 */
@Mixin(ClientPacketListener.class)
public abstract class ServeFeelClientMixin {
    @Inject(method = "handleBlockChangedAck", at = @At("HEAD"))
    private void sleep$ack(ClientboundBlockChangedAckPacket p, CallbackInfo ci) {
        if (dev.lull.ServeFeel.network()) dev.lull.ServeFeel.ack(p.sequence());
    }

    @Inject(method = "handlePlayerChat", at = @At("HEAD"))
    private void sleep$chat(ClientboundPlayerChatPacket p, CallbackInfo ci) {
        if (dev.lull.ServeFeel.network()) dev.lull.ServeFeel.chat(p.body().content());
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void sleep$system(ClientboundSystemChatPacket p, CallbackInfo ci) {
        if (dev.lull.ServeFeel.network()) dev.lull.ServeFeel.system(p.content().getString());
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void sleep$chunk(ClientboundLevelChunkWithLightPacket p, CallbackInfo ci) {
        if (dev.lull.ServeFeel.mainCall()) dev.lull.ServeFeel.chunk();   // (counted on the main thread: a count only)
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void sleep$corrected(ClientboundPlayerPositionPacket p, CallbackInfo ci) {
        if (dev.lull.ServeFeel.mainCall()) dev.lull.ServeFeel.corrected();   // (applied: a flight goes on from there)
    }

    @Inject(method = "handleMoveEntity", at = @At("HEAD"))
    private void sleep$entity(ClientboundMoveEntityPacket p, CallbackInfo ci) {
        if (dev.lull.ServeFeel.network()) dev.lull.ServeFeel.entityMove();
    }
}
