package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): chunk sending per player, the chunk packets built. */
@Mixin(net.minecraft.server.network.PlayerChunkSender.class)
public abstract class PipeScoutChunkSendMixin {
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void sleep$x0(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.nextStart(); }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void sleep$x1(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.nextEnd(); }

    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void sleep$c0(net.minecraft.server.network.ServerGamePacketListenerImpl l, net.minecraft.server.level.ServerLevel level,
                                 net.minecraft.world.level.chunk.LevelChunk chunk, CallbackInfo ci) { PipeScout.chunkStart(); }

    @Inject(method = "sendChunk", at = @At("RETURN"))
    private static void sleep$c1(net.minecraft.server.network.ServerGamePacketListenerImpl l, net.minecraft.server.level.ServerLevel level,
                                 net.minecraft.world.level.chunk.LevelChunk chunk, CallbackInfo ci) { PipeScout.chunkEnd(); }
}
