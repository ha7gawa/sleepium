package dev.lull.opt;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** part of SpecTicks / ServerParallelItems (1.20.1: the visible chunk map's lookup is protected) */
@Mixin(ChunkMap.class)
public interface ServerParallelItemsChunkMapAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder sleep$visible(long pos);
}
