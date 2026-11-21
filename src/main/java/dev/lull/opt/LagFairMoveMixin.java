package dev.lull.opt;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decoupled server, NON-EXACT option (notes/92, lab: {@code -Dlab.opt.LagFairMove=true}; off by default, a declared
 * difference from vanilla): the "moved too quickly" check stays fair when the server, not the player, was late.
 * <p>
 * Vanilla compares a move packet's distance from the player's position at the connection's last tick with 100 (300
 * gliding) times the move packets handled since that tick - but counts more than 5 as 1 (a client flooding packets).
 * When the server thread was slow (a stall, ticks over 250 ms), the packets an honest client sent meanwhile are all
 * handled in one burst after the tick: from the 6th on the allowance is one packet's while the distance keeps growing,
 * and the player is teleported back (rubber-banding; notes/92: a 2 s stall in a 20 blocks/s flight - pulled back
 * 11.5 blocks).
 * <p>
 * Here the flood limit is not 5 but what the packets' own arrival times allow: a client sends at most one move packet a
 * client tick (50 ms), so the n-th packet handled since the connection's last tick is honest if it arrived at least
 * (n - 2) x 50 ms after the first of them: limit {@code max(5, 2 + (arrival - first arrival) / 50 ms)}. The arrival is
 * taken on the network thread (the handler's first call, before it is queued for the server thread). A server that
 * keeps up gets exactly vanilla's limit (5 or fewer packets a tick); a burst sent faster than one a tick still counts
 * as 1.
 * <p>
 * The one difference from vanilla: a move packet that is the n-th (n > 5) handled since the connection's last tick,
 * with arrival times spread over at least (n - 2) client ticks, is judged against n packets' distance instead of 1 -
 * it may be accepted where vanilla teleports the player back. Nothing else (the other movement checks, the order of
 * handling) changes.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LagFairMoveMixin {
    @Shadow public ServerPlayer player;
    @Unique private final java.util.ArrayDeque<Long> sleep$arrivals = new java.util.ArrayDeque<>();
    @Unique private long sleep$first = -1;
    @Unique private int sleep$limit = 5;

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$tick(CallbackInfo ci) {
        this.sleep$first = -1;
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void sleep$arrival(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        long now = System.nanoTime();
        if (!this.player.getServer().isSameThread()) {   // the network thread: the packet is about to be queued
            synchronized (this.sleep$arrivals) {
                if (this.sleep$arrivals.size() < 4096) this.sleep$arrivals.add(now);
            }
            return;
        }
        Long a;
        synchronized (this.sleep$arrivals) {
            a = this.sleep$arrivals.poll();
        }
        long at = a == null ? now : a;
        if (this.sleep$first < 0) this.sleep$first = at;
        this.sleep$limit = (int) Math.max(5, Math.min(10_000, 2 + (at - this.sleep$first) / 50_000_000L));
    }

    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(intValue = 5))
    private int sleep$floodLimit(int five) {
        return this.sleep$limit;
    }
}
