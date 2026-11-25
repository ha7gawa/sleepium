package dev.lull.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.GenProfile;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

/** Measurement only (genbench): the whole main-thread body of the FULL step (new LevelChunk … ChunkEvent.Load). */
@Mixin(ChunkStatusTasks.class)
public abstract class GenProfileFullMixin {
    @WrapMethod(method = {"lambda$full$2", "method_60553"})
    private static ChunkAccess sleep$full(ChunkAccess chunk, WorldGenContext ctx, GenerationChunkHolder holder, Operation<ChunkAccess> original) {
        if (!GenProfile.active()) return original.call(chunk, ctx, holder);
        long t = System.nanoTime(), m = GenProfile.allocated();
        try {
            return original.call(chunk, ctx, holder);
        } finally {
            GenProfile.add("main: FULL body", System.nanoTime() - t, GenProfile.allocated() - m);
        }
    }
}
