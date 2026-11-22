package dev.lull.opt;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TicketReleaseAtFull (lab, notes/93, -Dlab.opt.TicketReleaseAtFull=true): a player-ticket throttler slot (4 in flight)
 * is freed when its chunk is FULL instead of when it is entity-ticking. Entity-ticking needs the chunk's neighbours
 * full as well: a slot was held for a whole ring's generation, and while a moving player's pipeline is behind the
 * next tickets waited for it. The ticket, its level and everything it loads are the same; only when the next ticket
 * may start changes (a moment vanilla's own timing could give it).
 */
@Mixin(DistanceManager.class)
public abstract class TicketReleaseAtFullMixin {
    @Redirect(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getEntityTickingChunkFuture()Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<LevelChunk>> sleep$atFull(ChunkHolder holder) {
        return holder.getFullChunkFuture();
    }
}
