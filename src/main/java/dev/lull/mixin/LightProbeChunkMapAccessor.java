package dev.lull.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Measurement only (-Dlab.light.fixcheck, 1.20.1): the chunk map's priority sorter (LightCheck.drain waits for it to be idle). */
@Mixin(ChunkMap.class)
public interface LightProbeChunkMapAccessor {
    @Accessor("queueSorter")
    ChunkTaskPriorityQueueSorter sleep$sorter();

    @org.spongepowered.asm.mixin.gen.Invoker("getVisibleChunkIfPresent")
    net.minecraft.server.level.ChunkHolder sleep$visible(long pos);

    @org.spongepowered.asm.mixin.gen.Invoker("getUpdatingChunkIfPresent")
    net.minecraft.server.level.ChunkHolder sleep$updating(long pos);
}
