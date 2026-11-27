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
    @Shadow protected long nextTickTime;   // 1.20.1: Util.getMillis() milliseconds (1.21: nextTickTimeNanos)

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
        FullRing.deadlineNanos = this.nextTickTime * 1_000_000L;   // the same instant in Util.getNanos() time
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
