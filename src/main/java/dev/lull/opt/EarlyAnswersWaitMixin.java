package dev.lull.opt;

import dev.lull.net.EarlyAnswers;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EarlyAnswers: the answers leave before the server thread sleeps. 1.20.1's MinecraftServer has no waitForTasks of its
 * own (BlockableEventLoop's runs, as for NetReactorWaitMixin); EarlyAnswers.beforeSleep acts only on the server thread
 * between ticks (the client's and the chunk executors' waits pass at the cost of a thread compare).
 */
@Mixin(BlockableEventLoop.class)
public abstract class EarlyAnswersWaitMixin {
    @Inject(method = "waitForTasks", at = @At("HEAD"))
    private void sleep$beforeSleep(CallbackInfo ci) {
        EarlyAnswers.beforeSleep();
    }
}
