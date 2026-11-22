package dev.lull.mixin;

import dev.lull.ChunkTimeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ChunkTimeline (notes/92, measurement): the player-ticket tracker asks for a ticket (into the throttler) */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class ServeMeterTimelineTrackerMixin {
    @Inject(method = "onLevelChange(JIZZ)V", at = @At("HEAD"))
    private void sleep$req(long chunkPos, int level, boolean hadTicket, boolean hasTicket, CallbackInfo ci) {
        if (ChunkTimeline.ON && !hadTicket && hasTicket) ChunkTimeline.stamp(chunkPos, ChunkTimeline.REQ);
    }
}
