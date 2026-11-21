package dev.lull.opt;

import java.util.List;
import java.util.Queue;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SaveTicks (optsupport/SaveWrite): a level chunk's pending and scheduled ticks, taken for the pool. */
@Mixin(LevelChunkTicks.class)
public interface SaveTicksLevelAccessor {
    @Accessor("pendingTicks")
    List<?> sleep$pendingTicks();

    @Accessor("tickQueue")
    Queue<?> sleep$tickQueue();
}
