package dev.lull.opt;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TicketReleaseEager (lab, notes/93, -Dlab.opt.TicketReleaseEager=true): the player-ticket throttler's slots are set
 * up for release in every DistanceManager.runAllUpdates pass.
 * <p>
 * Vanilla registers the release of the slots whose tickets were just added (ticketsToRelease: release when the chunk
 * is entity-ticking) only in a pass that had no chunk futures to update - its "else" branch. While a moving player
 * keeps the levels changing, nearly every pass has futures to update, so the registrations wait, the slots (4) stay
 * taken and the next tickets queue: at 30 ms ticks a ticket waited 4.4 s (notes/93). Here the same registration (the
 * same condition: a PLAYER ticket still there; the same release when entity-ticking, or FULL with
 * TicketReleaseAtFull) also runs at the end of the passes that returned early. Only when a slot frees changes.
 */
@Mixin(DistanceManager.class)
public abstract class TicketReleaseEagerMixin {
    @Shadow @Final LongSet ticketsToRelease;
    @Shadow @Final Executor mainThreadExecutor;
    @Shadow @Final ProcessorHandle<ChunkTaskPriorityQueueSorter.Release> ticketThrottlerReleaser;

    @Shadow
    private SortedArraySet<Ticket<?>> getTickets(long chunkPos) {
        throw new AssertionError();
    }

    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void sleep$eager(ChunkMap chunkMap, CallbackInfoReturnable<Boolean> cir) {
        if (this.ticketsToRelease.isEmpty()) return;   // (vanilla's else branch has done it)
        boolean atFull = Boolean.getBoolean("lab.opt.TicketReleaseAtFull");
        LongIterator it = this.ticketsToRelease.iterator();
        while (it.hasNext()) {
            long j = it.nextLong();
            if (this.getTickets(j).stream().anyMatch(t -> t.getType() == TicketType.PLAYER)) {
                ChunkHolder holder = ((TicketReleaseEagerAccessor) chunkMap).sleep$updating(j);
                if (holder == null) throw new IllegalStateException();
                CompletableFuture<ChunkResult<LevelChunk>> f = atFull ? holder.getFullChunkFuture() : holder.getEntityTickingChunkFuture();
                f.thenAccept(c -> this.mainThreadExecutor.execute(() -> this.ticketThrottlerReleaser.tell(ChunkTaskPriorityQueueSorter.release(() -> {}, j, false))));
            }
        }
        this.ticketsToRelease.clear();
    }
}
