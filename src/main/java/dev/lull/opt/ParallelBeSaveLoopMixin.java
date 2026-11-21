package dev.lull.opt;

import dev.lull.optsupport.ParallelBeSave;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ParallelBeSave: a pool save that hands a task to an event loop (the server's, a chunk cache's): a touch of the level. */
@Mixin(BlockableEventLoop.class)
public abstract class ParallelBeSaveLoopMixin {
    @Inject(method = "tell", at = @At("HEAD"))
    private void sleep$guard(Runnable task, CallbackInfo ci) {
        if (ParallelBeSave.active) ParallelBeSave.touch("BlockableEventLoop.tell");
    }
}
