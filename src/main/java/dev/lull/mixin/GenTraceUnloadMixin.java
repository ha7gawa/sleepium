package dev.lull.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Measurement only (-Dlab.pgen.trace=true): profiler sections inside a chunk's unload - its save, the level's unload. */
@Mixin(ChunkMap.class)
public abstract class GenTraceUnloadMixin {
    @Shadow @Final ServerLevel level;

    @WrapOperation(method = {"lambda$scheduleUnload$12", "method_60440"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z"))
    private boolean sleep$save(ChunkMap self, ChunkAccess chunk, Operation<Boolean> original) {
        level.getProfiler().push("unload_save");
        try {
            return original.call(self, chunk);
        } finally {
            level.getProfiler().pop();
        }
    }

    @WrapOperation(method = {"lambda$scheduleUnload$12", "method_60440"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void sleep$unload(ServerLevel self, LevelChunk chunk, Operation<Void> original) {
        level.getProfiler().push("unload_level");
        try {
            original.call(self, chunk);
        } finally {
            level.getProfiler().pop();
        }
    }
}
