package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** lab.fast: the spawning walk's tickChunk calls kept and done on the pool before the custom spawners (SpecTicks.chunkTicksPass) */
@Mixin(ServerChunkCache.class)
public abstract class ServerParallelItemsChunkTicksMixin {
    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void sleep$keep(ServerLevel level, LevelChunk chunk, int speed, Operation<Void> op) {
        if (!dev.lull.optsupport.SpecTicks.keepChunkTick(level, chunk, speed)) op.call(level, chunk, speed);
    }

    @Inject(method = "tickChunks", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=customSpawners"))
    private void sleep$pass(CallbackInfo ci) {
        dev.lull.optsupport.SpecTicks.chunkTicksPass();
    }
}
