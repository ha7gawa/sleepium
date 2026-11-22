package dev.lull.opt;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TicketNearFirst (lab, notes/93, -Dlab.opt.TicketNearFirst=true): DistanceManager's player-ticket throttler (a
 * ChunkTaskPriorityQueue named "player ticket throttler", 4 in flight) takes, within its first level (the ring at that
 * distance from a player), the chunk nearest a player's position one second ahead along its travel - not the one queued
 * first. The chunk is moved to the front of its level's map, then vanilla's pop takes it. Every queue of another name
 * (the main / light / worldgen sorters) is left as it is.
 */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class TicketNearFirstMixin<T> {
    @Shadow @Final private List<Long2ObjectLinkedOpenHashMap<List<Optional<T>>>> taskQueue;
    @Shadow private volatile int firstQueue;
    @Shadow @Final private String name;
    @Shadow @Final private LongSet acquired;
    @Shadow @Final private int maxTasks;

    @Inject(method = "pop", at = @At("HEAD"))
    private void sleep$nearFirst(CallbackInfoReturnable<?> cir) {
        if (!"player ticket throttler".equals(this.name) || this.acquired.size() >= this.maxTasks
                || this.firstQueue >= ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT) return;
        Long2ObjectLinkedOpenHashMap<List<Optional<T>>> level = this.taskQueue.get(this.firstQueue);
        if (level.size() < 2) return;
        long best = 0;
        double bestScore = Double.MAX_VALUE;
        int n = 0;
        for (var it = level.keySet().iterator(); it.hasNext() && n < 1024; n++) {
            long pos = it.nextLong();
            double s = dev.lull.optsupport.ChunkOrder.score(pos);
            if (s < bestScore) {
                bestScore = s;
                best = pos;
            }
        }
        if (bestScore < Double.MAX_VALUE) level.getAndMoveToFirst(best);
    }
}
