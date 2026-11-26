package dev.lull.opt;

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
    @Inject(method = "managedBlock", at = @At("HEAD"))
    private void sleep$blockOn(java.util.function.BooleanSupplier done, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        FullRing.blocking++;
    }

    @Inject(method = "managedBlock", at = @At("RETURN"))
    private void sleep$blockOff(java.util.function.BooleanSupplier done, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        FullRing.blocking--;
    }

    @Inject(method = "pollTask", at = @At("HEAD"), cancellable = true)
    private void sleep$ring(CallbackInfoReturnable<Boolean> cir) {
        if (FullRing.runOne()) cir.setReturnValue(true);
    }
}
