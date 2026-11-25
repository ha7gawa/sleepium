package dev.lull.opt;

import dev.lull.optsupport.FullRing;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** WorldgenReactor: the FULL step's executor puts its main-thread task into FullRing instead of the main mailbox. */
@Mixin(ChunkStatusTasks.class)
public abstract class ReactorFullMixin {
    @Inject(method = {"lambda$full$3", "method_60554"}, at = @At("HEAD"), cancellable = true)
    private static void sleep$ring(WorldGenContext ctx, ChunkPos pos, GenerationChunkHolder holder, Runnable task, CallbackInfo ci) {
        FullRing.offer(holder.getTicketLevel(), task);
        ci.cancel();
    }
}
