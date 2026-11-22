package dev.lull.opt;

import dev.lull.net.EarlyAnswers;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EarlyAnswers' hand-over points, both on the server thread between ticks: right before a tick starts (a server that is
 * behind runs the players' packets, then the next tick at once); before the thread sleeps: EarlyAnswersWaitMixin
 * (1.20.1's MinecraftServer has no waitForTasks of its own).
 */
@Mixin(MinecraftServer.class)
public abstract class EarlyAnswersServerMixin {
    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"))
    private void sleep$beforeTick(CallbackInfo ci) {
        EarlyAnswers.beforeTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V", shift = At.Shift.AFTER))
    private void sleep$afterTick(CallbackInfo ci) {
        EarlyAnswers.afterTick();
    }
}
