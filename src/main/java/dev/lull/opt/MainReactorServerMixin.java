package dev.lull.opt;

import dev.lull.optsupport.MainReactor;
import java.util.Queue;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ServerReactor stage 1 (MainReactor): the idle wait between ticks sleeps until work or the next tick. */
@Mixin(MinecraftServer.class)
public abstract class MainReactorServerMixin {
    @Shadow protected long nextTickTimeNanos;
    @Shadow private long delayedTasksMaxNextTickTimeNanos;
    @Shadow private boolean mayHaveDelayedTasks;
    @Shadow private long idleTimeNanos;

    @Shadow
    protected abstract boolean isTickTimeLoggingEnabled();

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @org.spongepowered.asm.mixin.Unique private Queue<?>[] sleep$queues;
    @org.spongepowered.asm.mixin.Unique private int sleep$levelCount = -1;

    /** the server's own task queue and each level's chunk executor queue (rebuilt if the set of levels changes) */
    @org.spongepowered.asm.mixin.Unique
    private Queue<?>[] sleep$queues() {
        int n = 0;
        for (ServerLevel ignored : this.getAllLevels()) n++;
        if (n != sleep$levelCount || sleep$queues == null) {
            java.util.ArrayList<Queue<?>> qs = new java.util.ArrayList<>(n + 1);
            qs.add(((EventLoopQueueAccessor) this).sleep$pending());
            for (ServerLevel level : this.getAllLevels())
                qs.add(((EventLoopQueueAccessor) dev.lull.optsupport.Reflect.get(level.getChunkSource(), "mainThreadProcessor")).sleep$pending());
            sleep$queues = qs.toArray(new Queue<?>[0]);
            sleep$levelCount = n;
        }
        return sleep$queues;
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "waitUntilNextTick")
    private void sleep$idle(com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        MainReactor.idle = true;
        try {
            original.call();
        } finally {
            MainReactor.idle = false;
        }
    }

    /**
     * @author probe
     * @reason in the idle phase, sleep until work or the deadline instead of 100 µs polls; elsewhere vanilla
     */
    @Overwrite
    public void waitForTasks() {
        dev.lull.net.NetReactor.beforeSleep();   // what the thread just sent leaves before it sleeps
        boolean flag = this.isTickTimeLoggingEnabled();
        long i = flag ? Util.getNanos() : 0L;
        if (MainReactor.idle) {
            long deadline = this.mayHaveDelayedTasks ? this.delayedTasksMaxNextTickTimeNanos : this.nextTickTimeNanos;
            MainReactor.park(deadline, sleep$queues());
        } else {   // vanilla's wait (super.waitForTasks), with CondWakeMixin's arming that super would have done
            java.util.concurrent.atomic.AtomicBoolean armed = dev.lull.optsupport.WakeFlags.current();
            armed.set(true);
            try {
                Thread.yield();
                LockSupport.parkNanos("waiting for tasks", 100000L);
            } finally {
                armed.set(false);
            }
        }
        if (flag) {
            this.idleTimeNanos = this.idleTimeNanos + (Util.getNanos() - i);
        }
    }
}
