package dev.lull.opt;

import net.minecraft.util.profiling.ActiveProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * lab parallel runs (mob runs, item runs, chunk tick passes) with vanilla's profiler on (/debug start, /perf start): the
 * profiler is its creating thread's alone. Code keeps it across a run (ServerLevel.tick's entity lambda, mobs' goal
 * selectors) and called it from the workers - ConcurrentModificationException, the server stopped. Calls from other
 * threads during a run are dropped. ActiveProfiler exists only while profiling: no cost otherwise.
 */
@Mixin(ActiveProfiler.class)
public abstract class ServerParallelItemsActiveProfilerMixin {
    @Unique private Thread sleep$owner;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$owner(CallbackInfo ci) {
        sleep$owner = Thread.currentThread();
    }

    @Inject(method = {"push(Ljava/lang/String;)V", "push(Ljava/util/function/Supplier;)V", "pop", "popPush(Ljava/lang/String;)V",
            "popPush(Ljava/util/function/Supplier;)V", "markForCharting", "incrementCounter(Ljava/lang/String;I)V",
            "incrementCounter(Ljava/util/function/Supplier;I)V"}, at = @At("HEAD"), cancellable = true)
    private void sleep$foreign(CallbackInfo ci) {
        if (Thread.currentThread() != sleep$owner && (dev.lull.optsupport.SpecTicks.running() || dev.lull.optsupport.ServerParallelItems.running())) ci.cancel();
    }
}
