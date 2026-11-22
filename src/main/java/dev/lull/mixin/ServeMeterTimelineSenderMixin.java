package dev.lull.mixin;

import dev.lull.ChunkTimeline;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ChunkTimeline (notes/92, measurement): a chunk written for a player */
@Mixin(PlayerChunkSender.class)
public abstract class ServeMeterTimelineSenderMixin {
    @Inject(method = "sendChunk", at = @At("HEAD"))
    private static void sleep$sent(ServerGamePacketListenerImpl listener, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        ChunkTimeline.stamp(chunk.getPos().toLong(), ChunkTimeline.SENT);
    }
}
