package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FullRing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldgenReactor: the level's main-thread executor also serves FullRing — within the idle budget when called from
 * the idle loop, without a budget when the main thread blocks on a chunk future (managedBlock).
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class ReactorExecutorMixin {
    /** the main thread waits for a chunk future: FullRing runs without limits meanwhile (issue #15) */
    @WrapMethod(method = "managedBlock")
    private void sleep$block(java.util.function.BooleanSupplier done, Operation<Void> original) {
        FullRing.blocking++;
        try {
            original.call(done);
        } finally {
            FullRing.blocking--;
        }
    }

    @Inject(method = "pollTask", at = @At("HEAD"), cancellable = true)
    private void sleep$ring(CallbackInfoReturnable<Boolean> cir) {
        if (FullRing.runOne()) cir.setReturnValue(true);
    }
}
