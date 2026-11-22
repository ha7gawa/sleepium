package dev.lull.mixin;

import dev.lull.ChunkTimeline;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ChunkTimeline (notes/92, measurement): a player's ticket added (after the throttler and its main-thread hop) */
@Mixin(DistanceManager.class)
public abstract class ServeMeterTimelineTicketMixin {
    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("HEAD"))
    private void sleep$add(long chunkPos, Ticket<?> ticket, CallbackInfo ci) {
        if (ChunkTimeline.ON && ticket.getType() == TicketType.PLAYER) ChunkTimeline.stamp(chunkPos, ChunkTimeline.ADD);
    }
}
