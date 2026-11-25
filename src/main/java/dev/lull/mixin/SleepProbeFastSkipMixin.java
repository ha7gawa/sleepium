package dev.lull.mixin;

import dev.lull.optsupport.sleep.ChunkTickMemo;
import dev.lull.optsupport.sleep.SleepEngine;
import dev.lull.optsupport.sleep.SleeperHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast skip of a sleeping block entity at the head of its tick, before vanilla's own checks and the profiler.
 * A skipped tick is counted only when vanilla would really have run the ticker: not removed, inside the world
 * border, chunk block-ticking with entities loaded (vanilla LevelChunk.isTicking, memoized per chunk per tick).
 * The block state stays valid while asleep: any block change at its position wakes it first. Sleep engine only.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class SleepProbeFastSkipMixin {
    @Shadow @Final private BlockEntity blockEntity;
    @Shadow(aliases = {"this$0", "field_27223"}) @Final private LevelChunk this$0;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sleep$fastSkip(CallbackInfo ci) {
        BlockEntity be = blockEntity;
        SleeperHolder h = (SleeperHolder) be;
        long wakeAt = h.sleep$wakeAt();
        if (wakeAt == Long.MIN_VALUE) return;
        Level level = be.getLevel();
        if (level == null || be.isRemoved() || level.getGameTime() >= wakeAt) return;
        BlockPos pos = be.getBlockPos();
        if (!level.getWorldBorder().isWithinBounds(pos)) return;   // vanilla would not tick either
        LevelChunk chunk = this$0;
        ChunkTickMemo memo = (ChunkTickMemo) chunk;
        long now = level.getGameTime();
        boolean ticking;
        if (memo.sleep$memoTick() == now) {
            ticking = memo.sleep$memoTicking();
        } else {
            ticking = !(level instanceof ServerLevel sl)
                    || chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING) && sl.areEntitiesLoaded(ChunkPos.asLong(pos));
            memo.sleep$setMemo(now, ticking);
        }
        if (!ticking) return;   // vanilla returns without ticking; nothing to count
        if (!SleepEngine.verifyAsleep(be)) return;   // changed from outside: let it tick (and wake) now
        h.sleep$setSkipped(h.sleep$skipped() + 1);
        SleepEngine.fastSkipped++;
        ci.cancel();
    }
}
