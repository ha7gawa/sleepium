package dev.lull.opt;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** EarlyAnswers: the chunk holders around a player whose block changes go out with the acknowledgement */
@Mixin(ChunkMap.class)
public interface EarlyAnswersChunkMapAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder sleep$visible(long chunkPos);
}
