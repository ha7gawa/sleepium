package dev.lull.mixin;

import dev.lull.ChunkTimeline;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ChunkTimeline (notes/92, measurement): generation's noise start, the light step's and the full step's completion */
@Mixin(ChunkStatusTasks.class)
public abstract class ServeMeterTimelineTasksMixin {
    @Inject(method = "generateNoise", at = @At("HEAD"))
    private static void sleep$noise(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkTimeline.stamp(chunk.getPos().toLong(), ChunkTimeline.NOISE);
    }

    @Inject(method = "light", at = @At("RETURN"))
    private static void sleep$lit(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                  CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!ChunkTimeline.ON) return;
        long pos = chunk.getPos().toLong();
        cir.getReturnValue().thenRun(() -> ChunkTimeline.stamp(pos, ChunkTimeline.LIT));
    }

    @Inject(method = "full", at = @At("RETURN"))
    private static void sleep$full(WorldGenContext ctx, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                   CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!ChunkTimeline.ON) return;
        long pos = chunk.getPos().toLong();
        cir.getReturnValue().thenRun(() -> ChunkTimeline.stamp(pos, ChunkTimeline.FULL));
    }
}
