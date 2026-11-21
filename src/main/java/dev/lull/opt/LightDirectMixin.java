package dev.lull.opt;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LightDirect (-Dlab.opt.LightDirect, notes/84): light tasks go straight into the level's light mailbox instead of
 * through the chunk priority sorter.
 *
 * <p>Vanilla hands every light task (initializeLight / lightChunk pre and post, checkBlock, section status ...) to the
 * ChunkTaskPriorityQueueSorter, which forwards one chunk's tasks at a time to the light mailbox and waits for them to be
 * taken before it forwards the next chunk's: two or three hops over the busy worker pool per chunk. Measured (Xeon
 * .103, genbench 32 x 32): the light actor busy 22-25 % of the time, yet initializeLight's future 2.2 s and
 * lightChunk's 1.2 s on average from call to done - chunks waited in the sorter, not for light.
 *
 * <p>Here the task the sorter would deliver (the same lambda: added to lightTasks, a batch run at 1000) goes to the
 * light mailbox at once, through LightLanes: in the sorter's priority order (queue level at submission, level 0 - the
 * structure changes vanilla marks urgent - first), FIFO within a level. Same tasks, same lambdas; only which queued task
 * the actor takes next can differ from the sorter's (its per-chunk grouping within a level, its re-sorting when a chunk's
 * level changes while queued) - schedules vanilla produces too depending on timing. -Dlab.light.prio=false: one FIFO.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightDirectMixin {
    @Shadow @Final private ProcessorMailbox<Runnable> taskMailbox;
    @Unique private static final ProcessorHandle<Unit> sleep$NO_REPLY = ProcessorHandle.of("light direct (no reply)", u -> {});
    @Unique private final dev.lull.optsupport.LightLanes sleep$lanes = new dev.lull.optsupport.LightLanes();

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ProcessorHandle;tell(Ljava/lang/Object;)V"))
    private void sleep$direct(ProcessorHandle sorter, Object message) {
        LightDirectMessageAccessor m = (LightDirectMessageAccessor) message;
        Runnable task = (Runnable) m.sleep$task().apply(sleep$NO_REPLY);
        if (dev.lull.optsupport.LightLanes.PRIO) sleep$lanes.submit(m.sleep$level().getAsInt(), task, taskMailbox);
        else taskMailbox.tell(task);
    }
}
