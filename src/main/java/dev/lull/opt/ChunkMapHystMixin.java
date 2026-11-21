package dev.lull.opt;

import dev.lull.optsupport.Hysteresis;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hysteresis: a player's tracking view is the disc of R + K around its anchor; the anchor holds the player tickets. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapHystMixin {
    @Shadow @Final private net.minecraft.server.level.PlayerMap playerMap;
    @Shadow @Final private ChunkMap.DistanceManager distanceManager;

    @Shadow
    abstract int getPlayerViewDistance(ServerPlayer player);

    @Shadow
    private void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView view) {
        throw new AssertionError();
    }

    @Unique private final Map<ServerPlayer, ChunkPos> sleep$anchor = new IdentityHashMap<>();
    /** the anchor holding a ticket for the player (players the map ignores - spectators not generating - hold none) */
    @Unique private final Map<ServerPlayer, ChunkPos> sleep$ticket = new IdentityHashMap<>();

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void sleep$track(ServerPlayer player, CallbackInfo ci) {
        if (!Hysteresis.ON) return;
        ci.cancel();
        ChunkPos a = Hysteresis.anchor(sleep$anchor.get(player), player.chunkPosition());
        sleep$anchor.put(player, a);
        ChunkPos want = playerMap.ignored(player) ? null : a, have = sleep$ticket.get(player);
        if (want == null ? have != null : !want.equals(have)) {
            Hysteresis.Anchors am = (Hysteresis.Anchors) distanceManager;
            if (have != null) am.sleep$anchorRemove(have.toLong());
            if (want != null) {
                am.sleep$anchorAdd(want.toLong());
                sleep$ticket.put(player, want);
            } else sleep$ticket.remove(player);
        }
        ChunkTrackingView v = ChunkTrackingView.of(a, getPlayerViewDistance(player) + Hysteresis.K);
        if (!v.equals(player.getChunkTrackingView())) applyChunkTrackingView(player, v);
    }

    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void sleep$status(ServerPlayer player, boolean track, CallbackInfo ci) {
        if (!Hysteresis.ON || track) return;
        sleep$anchor.remove(player);
        ChunkPos have = sleep$ticket.remove(player);
        if (have != null) ((Hysteresis.Anchors) distanceManager).sleep$anchorRemove(have.toLong());
    }
}
