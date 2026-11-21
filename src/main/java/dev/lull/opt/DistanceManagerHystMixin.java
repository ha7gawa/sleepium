package dev.lull.opt;

import dev.lull.optsupport.Hysteresis;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hysteresis: the player ticket tracker's sources are the anchors, its distance R + K (see Hysteresis). */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerHystMixin implements Hysteresis.Anchors {
    @Shadow @Final private DistanceManager.PlayerTicketTracker playerTicketManager;
    @Unique private final Long2IntOpenHashMap sleep$anchors = new Long2IntOpenHashMap();

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32))
    private int sleep$range(int v) {
        return Hysteresis.ON ? v + Hysteresis.K : v;
    }

    @Redirect(method = {"addPlayer", "removePlayer"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;update(JIZ)V"))
    private void sleep$playerSource(DistanceManager.PlayerTicketTracker t, long pos, int level, boolean decreasing) {
        if (!Hysteresis.ON) t.update(pos, level, decreasing);   // with hysteresis the anchors are the sources
    }

    @Redirect(method = "updatePlayerTickets", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;updateViewDistance(I)V"))
    private void sleep$distance(DistanceManager.PlayerTicketTracker t, int viewDistance) {
        t.updateViewDistance(Hysteresis.ON ? viewDistance + Hysteresis.K : viewDistance);
    }

    @Override
    public void sleep$anchorAdd(long chunk) {
        if (sleep$anchors.addTo(chunk, 1) == 0) playerTicketManager.update(chunk, 0, true);
    }

    @Override
    public void sleep$anchorRemove(long chunk) {
        int n = sleep$anchors.addTo(chunk, -1);
        if (n <= 1) {
            sleep$anchors.remove(chunk);
            playerTicketManager.update(chunk, Integer.MAX_VALUE, false);
        }
    }

    @Override
    public boolean sleep$hasAnchor(long chunk) {
        return sleep$anchors.get(chunk) > 0;
    }
}
