package dev.lull.mixin;

import dev.lull.ChunkTimeline;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ChunkTimeline (notes/92, measurement): a chunk ready to send; a chunk entering a player's view */
@Mixin(ChunkMap.class)
public abstract class ServeMeterTimelineSendMixin {
    @Inject(method = "onChunkReadyToSend", at = @At("HEAD"))
    private void sleep$ready(LevelChunk chunk, CallbackInfo ci) {
        ChunkTimeline.stamp(chunk.getPos().toLong(), ChunkTimeline.READY);
    }

    @Inject(method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"))
    private void sleep$view(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        ChunkTimeline.stamp(pos.toLong(), ChunkTimeline.VIEW);
    }
}
