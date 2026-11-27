package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FullRing;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * WorldgenReactor (1.20.1: ServerChunkCache's main-thread executor does not override managedBlock - the hook is in
 * BlockableEventLoop, counting only that executor's waits; the server's own idle wait keeps FullRing's limits): the main
 * thread waits for a chunk future, so FullRing runs without limits meanwhile (issue #15).
 */
@Mixin(BlockableEventLoop.class)
public abstract class ReactorBlockingMixin {
    @WrapMethod(method = "managedBlock")
    private void sleep$block(java.util.function.BooleanSupplier done, Operation<Void> original) {
        if (!sleep$chunkExecutor()) {
            original.call(done);
            return;
        }
        FullRing.blocking++;
        try {
            original.call(done);
        } finally {
            FullRing.blocking--;
        }
    }

    @Unique
    private boolean sleep$chunkExecutor() {
        return this.getClass().getName().endsWith("ServerChunkCache$MainThreadExecutor") || this.getClass().getSimpleName().equals("MainThreadExecutor")
                || dev.lull.Names.official(this.getClass().getName()).endsWith("ServerChunkCache$MainThreadExecutor");
    }
}
