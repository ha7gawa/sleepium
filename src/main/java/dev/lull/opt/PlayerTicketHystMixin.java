package dev.lull.opt;

import dev.lull.optsupport.Hysteresis;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Hysteresis: the player ticket tracker's source chunks are the anchors (an override of getLevelFromSource). */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlayerTicketHystMixin {
    @Shadow @Final DistanceManager this$0;

    protected int getLevelFromSource(long pos) {
        if (Hysteresis.ON) return ((Hysteresis.Anchors) (Object) this$0).sleep$hasAnchor(pos) ? 0 : Integer.MAX_VALUE;
        var set = this$0.playersPerChunk.get(pos);   // FixedPlayerDistanceChunkTracker.havePlayer
        return set != null && !set.isEmpty() ? 0 : Integer.MAX_VALUE;
    }
}
