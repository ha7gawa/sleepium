package dev.lull.opt;

import java.util.function.Function;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** MainDirect: the task a priority-sorter message carries (a main-thread Runnable once given its reply handle) */
@Mixin(ChunkTaskPriorityQueueSorter.Message.class)
public interface MainDirectMessageAccessor {
    @Accessor("task")
    Function<ProcessorHandle<Unit>, Runnable> sleep$task();
}
