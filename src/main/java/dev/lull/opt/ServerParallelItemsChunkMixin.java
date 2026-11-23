package dev.lull.opt;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of ServerParallelItems: a worker's chunk lookup during a run, served from the chunks resolved before it. */
@Mixin(ServerChunkCache.class)
public abstract class ServerParallelItemsChunkMixin {
    @Shadow @Final Thread mainThread;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void sleep$chunk(int x, int z, ChunkStatus status, boolean require, CallbackInfoReturnable<ChunkAccess> cir) {
        if (dev.lull.optsupport.SpecTicks.inJob() && Thread.currentThread() != mainThread) {   // (SpecTicks' own jobs only: world generation on its own threads keeps vanilla's lookup)
            cir.setReturnValue(dev.lull.optsupport.SpecTicks.chunk((ServerChunkCache) (Object) this, x, z, require));
            return;
        }
        if (dev.lull.optsupport.ServerParallelItems.inJob() && Thread.currentThread() != mainThread) {
            LevelChunk c = dev.lull.optsupport.ServerParallelItems.chunk(x, z);
            if (c != null) cir.setReturnValue(c);
        }
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void sleep$chunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (dev.lull.optsupport.SpecTicks.inJob() && Thread.currentThread() != mainThread) {   // (SpecTicks' own jobs only: world generation on its own threads keeps vanilla's lookup)
            cir.setReturnValue(dev.lull.optsupport.SpecTicks.chunk((ServerChunkCache) (Object) this, x, z, false));
            return;
        }
        if (dev.lull.optsupport.ServerParallelItems.inJob() && Thread.currentThread() != mainThread)
            cir.setReturnValue(dev.lull.optsupport.ServerParallelItems.chunk(x, z));
    }
}
