package dev.lull.opt;

import dev.lull.optsupport.ParallelBeSave;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ParallelBeSave: off the main thread ServerChunkCache.getChunk hands the lookup to the main thread and waits (the main
 * thread waits for the fork: a deadlock), getChunkNow returns null: either is a touch of the level by a pool save.
 */
@Mixin(ServerChunkCache.class)
public abstract class ParallelBeSaveChunkMixin {
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"))
    private void sleep$guard(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> cir) {
        if (ParallelBeSave.active) ParallelBeSave.touch("ServerChunkCache.getChunk");
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"))
    private void sleep$guardNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (ParallelBeSave.active) ParallelBeSave.touch("ServerChunkCache.getChunkNow");
    }
}
