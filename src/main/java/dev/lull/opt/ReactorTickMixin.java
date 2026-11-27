package dev.lull.opt;

import dev.lull.optsupport.FullRing;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldgenReactor: FullRing's feedback — each server tick's duration adjusts its window; the idle phase's deadline
 * (the next tick's start) bounds what it may run between ticks.
 */
@Mixin(MinecraftServer.class)
public abstract class ReactorTickMixin {
    @Shadow protected long nextTickTimeNanos;
    @Shadow private long delayedTasksMaxNextTickTimeNanos;
    @Shadow private boolean mayHaveDelayedTasks;

    @Unique private long sleep$tickStart;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void sleep$start(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        FullRing.shuttingDown = false;
        sleep$tickStart = System.nanoTime();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void sleep$end(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        FullRing.onTick(System.nanoTime() - sleep$tickStart);
    }

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"))
    private void sleep$deadline(CallbackInfo ci) {
        FullRing.deadlineNanos = this.nextTickTimeNanos;
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sleep$stopOn(CallbackInfo ci) {
        FullRing.shuttingDown = true;
        FullRing.idle = false;
        FullRing.blocking = 0;
        dev.lull.optsupport.MainReactor.idle = false;
    }

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void sleep$stopOff(CallbackInfo ci) {
        FullRing.shuttingDown = false;
    }
}
