package dev.lull.opt;

import java.util.Queue;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TaskBudget's split (-Dlab.taskBudget.split): the next queued task, to time it by its packet. Part of TaskBudget. */
@Mixin(BlockableEventLoop.class)
public interface TaskQueueAccessor {
    @Accessor("pendingRunnables")
    Queue<?> sleep$pending();
}
