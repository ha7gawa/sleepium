package dev.lull.opt;

import java.util.function.Function;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LightDirect: the task a priority-sorter message carries. */
@Mixin(ChunkTaskPriorityQueueSorter.Message.class)
public interface LightDirectMessageAccessor {
    @Accessor("task")
    Function<ProcessorHandle<Unit>, ?> sleep$task();
}
