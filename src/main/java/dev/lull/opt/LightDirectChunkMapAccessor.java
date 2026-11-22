package dev.lull.opt;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** LightDirect: is a chunk's holder in the visible map - the map the light engine reads chunks through (LightLanes). */
@Mixin(ChunkMap.class)
public interface LightDirectChunkMapAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder sleep$visible(long pos);
}
