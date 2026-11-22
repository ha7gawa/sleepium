package dev.lull.opt;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MainDirect (lab, notes/93, -Dlab.opt.MainDirect=true): the chunk map's main-thread chunk tasks (the FULL step, a
 * chunk's ticking / accessible promotion, NeoForge's scheduleOnMainThreadMailbox) go straight to the main-thread
 * executor instead of through the chunk priority sorter - the main-thread counterpart of LightDirect (notes/84).
 *
 * <p>The sorter forwards ONE chunk's main-thread tasks at a time and waits until the main thread has run them before it
 * forwards the next (ProcessorHandle.ask, then a pollTask on the sorter's own mailbox on the worker pool): every chunk a
 * round trip main thread -> pool -> main thread. On a busy server with the pool full of generation work that round trip
 * is slow: with the player-ticket throttle lifted, the FULL step's main task waited 8.3 s (p90 8.8) from its submission
 * to its run at 30 ms ticks, the chunk mailbox empty at every tick end (notes/93).
 *
 * <p>Here the task the sorter would deliver (the same lambda, its reply to the sorter dropped) is handed to the
 * main-thread executor at once, FIFO. Same tasks, each on the main thread as before; only the order between different
 * chunks' tasks can differ from the sorter's level order - a schedule vanilla produces too, depending on timing (which
 * chunk the sorter takes next depends on when the previous one finished). The WorldGenContext (the FULL step's
 * handle) is rebuilt with the direct handle.
 */
@Mixin(ChunkMap.class)
public abstract class MainDirectMixin {
    @Shadow @Final @Mutable private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> mainThreadMailbox;
    @Shadow @Final @Mutable private WorldGenContext worldGenContext;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;
    @Unique private static final ProcessorHandle<Unit> sleep$NO_REPLY = ProcessorHandle.of("main direct (no reply)", u -> {});

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$direct(CallbackInfo ci) {
        BlockableEventLoop<Runnable> exec = this.mainThreadExecutor;
        ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> direct = ProcessorHandle.of("main direct",
                m -> exec.execute(((MainDirectMessageAccessor) (Object) m).sleep$task().apply(sleep$NO_REPLY)));
        this.mainThreadMailbox = direct;
        WorldGenContext c = this.worldGenContext;
        this.worldGenContext = new WorldGenContext(c.level(), c.generator(), c.structureManager(), c.lightEngine(), direct);
    }
}
