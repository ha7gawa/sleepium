package dev.lull.opt;

import dev.lull.optsupport.FullRing;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldgenReactor (1.20.1: ServerChunkCache's main-thread executor does not override managedBlock - the hook is in
 * BlockableEventLoop, counting only that executor's waits; the server's own idle wait keeps FullRing's limits): the main
 * thread waits for a chunk future, so FullRing runs without limits meanwhile (issue #15).
 */
@Mixin(BlockableEventLoop.class)
public abstract class ReactorBlockingMixin {
    @Inject(method = "managedBlock", at = @At("HEAD"))
    private void sleep$blockOn(java.util.function.BooleanSupplier done, CallbackInfo ci) {
        if (sleep$chunkExecutor()) FullRing.blocking++;
    }

    @Inject(method = "managedBlock", at = @At("RETURN"))
    private void sleep$blockOff(java.util.function.BooleanSupplier done, CallbackInfo ci) {
        if (sleep$chunkExecutor()) FullRing.blocking--;
    }

    private boolean sleep$chunkExecutor() {
        return this.getClass().getName().endsWith("ServerChunkCache$MainThreadExecutor") || this.getClass().getSimpleName().equals("MainThreadExecutor")
                || dev.lull.Names.official(this.getClass().getName()).endsWith("ServerChunkCache$MainThreadExecutor");
    }
}
