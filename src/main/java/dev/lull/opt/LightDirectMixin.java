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
 * <p>Here the task the sorter would deliver (the same lambda: added to lightTasks, a batch run at 1000) is told to the
 * light mailbox at once, FIFO in submission order. Same tasks, same per-thread order, each chunk's tasks in the order
 * they were submitted; only the cross-chunk order differs from the sorter's priority order - a schedule vanilla produces
 * too whenever the sorter had already forwarded the earlier tasks. Light values: after generation they are the fixed
 * point of the blocks (LightCheck: 0 of 40 k sections differ from a from-scratch relight), not a function of task order.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightDirectMixin {
    @Shadow @Final private ProcessorMailbox<Runnable> taskMailbox;
    @Unique private static final ProcessorHandle<Unit> sleep$NO_REPLY = ProcessorHandle.of("light direct (no reply)", u -> {});

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ProcessorHandle;tell(Ljava/lang/Object;)V"))
    private void sleep$direct(ProcessorHandle sorter, Object message) {
        Object task = ((LightDirectMessageAccessor) message).sleep$task().apply(sleep$NO_REPLY);
        taskMailbox.tell((Runnable) task);
    }
}
