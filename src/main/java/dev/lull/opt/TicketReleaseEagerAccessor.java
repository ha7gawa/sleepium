package dev.lull.opt;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** TicketReleaseEager: the holder whose throttler slot is set up for release */
@Mixin(ChunkMap.class)
public interface TicketReleaseEagerAccessor {
    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder sleep$updating(long chunkPos);
}
