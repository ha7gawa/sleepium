package dev.lull.mixin;

import dev.lull.GenProfile;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (genbench): how long main-thread tasks wait in their event loop's queue (tell → run). */
@Mixin(BlockableEventLoop.class)
public abstract class GenProfileQueueMixin<R extends Runnable> {
    @Unique private static final ConcurrentHashMap<Object, Long> PROBE$ENQUEUED = new ConcurrentHashMap<>();
    @Shadow @Final private java.util.Queue<R> pendingRunnables;
    @Unique private static final java.util.concurrent.atomic.AtomicInteger PROBE$TRACES = new java.util.concurrent.atomic.AtomicInteger();

    /** what a task is: a completion stage names its function's class, a plain task its own class */
    @Unique
    private static String sleep$kind(Object task) {
        Object t = task;
        if (t instanceof net.minecraft.server.TickTask tt) t = dev.lull.optsupport.Reflect.get(tt, "runnable");
        String n = t.getClass().getName();
        if (n.startsWith("java.util.concurrent.CompletableFuture$")) {
            try {
                Object fn = dev.lull.optsupport.Reflect.get(t, "fn");
                if (fn != null) n = n.substring(n.lastIndexOf('$') + 1) + ":" + fn.getClass().getName();
            } catch (RuntimeException ignored) {
            }
        }
        int k = n.indexOf("$$Lambda");
        return k > 0 ? n.substring(0, k) + "$$Lambda" : n;
    }

    @Inject(method = "tell", at = @At("HEAD"))
    private void sleep$in(R task, CallbackInfo ci) {
        if (GenProfile.active()) {
            PROBE$ENQUEUED.put(task, System.nanoTime());
            // 11-21: the queue's length when a task arrives (us/call column = tasks ahead of it) and who told it
            GenProfile.add("main: queue length at tell (us = tasks), " + ((BlockableEventLoop<?>) (Object) this).name(), this.pendingRunnables.size() * 1000L, 0);
            GenProfile.add("main: told from " + (Thread.currentThread().getName().startsWith("Server thread") ? "the server thread" : "other threads") + ", " + ((BlockableEventLoop<?>) (Object) this).name() + " / " + sleep$kind(task), 0, 0);
            if (task.getClass().getName().endsWith("CompletableFuture$AsyncSupply") && PROBE$TRACES.incrementAndGet() <= 2) {
                StringBuilder b = new StringBuilder("sleep: AsyncSupply to " + ((BlockableEventLoop<?>) (Object) this).name() + " from " + Thread.currentThread().getName());
                StackTraceElement[] st = new Throwable().getStackTrace();
                for (int i = 1; i < Math.min(st.length, 16); i++) b.append(System.lineSeparator()).append("    at ").append(st[i]);
                org.slf4j.LoggerFactory.getLogger("sleep").info(b.toString());
            }
        }
    }

    @Inject(method = "doRunTask", at = @At("HEAD"))
    private void sleep$out(R task, CallbackInfo ci) {
        Long t = PROBE$ENQUEUED.remove(task);
        if (t != null && GenProfile.active())
            GenProfile.add("main: queue wait, " + ((BlockableEventLoop<?>) (Object) this).name() + " / " + sleep$kind(task), System.nanoTime() - t, 0);
    }
}
