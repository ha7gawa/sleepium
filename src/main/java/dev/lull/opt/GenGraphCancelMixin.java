package dev.lull.opt;

import dev.lull.optsupport.GenGraph;
import net.minecraft.server.level.ChunkGenerationTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GenGraph: a cancelled task (ticket lowered or re-targeted) lets the graph prune what only it needed (main thread, rare). */
@Mixin(ChunkGenerationTask.class)
public abstract class GenGraphCancelMixin {
    @Inject(method = "markForCancellation", at = @At("HEAD"))
    private void sleep$cancel(CallbackInfo ci) {
        GenGraph.cancel((ChunkGenerationTask) (Object) this);
    }
}
