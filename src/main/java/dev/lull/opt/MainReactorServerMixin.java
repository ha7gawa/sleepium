package dev.lull.opt;

import dev.lull.optsupport.MainReactor;
import java.util.Queue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ServerReactor stage 1 (MainReactor): the idle wait between ticks sleeps until work or the next tick. */
@Mixin(MinecraftServer.class)
public abstract class MainReactorServerMixin implements MainReactor.Server {
    @Shadow protected long nextTickTime;   // 1.20.1: milliseconds (1.21: nextTickTimeNanos)
    @Shadow private long delayedTasksMaxNextTickTime;
    @Shadow private boolean mayHaveDelayedTasks;

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @org.spongepowered.asm.mixin.Unique private Queue<?>[] sleep$queueArray;
    @org.spongepowered.asm.mixin.Unique private int sleep$levelCount = -1;

    /** the server's own task queue and each level's chunk executor queue (rebuilt if the set of levels changes) */
    @Override
    public Queue<?>[] sleep$queues() {
        int n = 0;
        for (ServerLevel ignored : this.getAllLevels()) n++;
        if (n != sleep$levelCount || sleep$queueArray == null) {
            java.util.ArrayList<Queue<?>> qs = new java.util.ArrayList<>(n + 1);
            qs.add(((EventLoopQueueAccessor) this).sleep$pending());
            for (ServerLevel level : this.getAllLevels())
                qs.add(((EventLoopQueueAccessor) dev.lull.optsupport.Reflect.get(level.getChunkSource(), "mainThreadProcessor")).sleep$pending());
            sleep$queueArray = qs.toArray(new Queue<?>[0]);
            sleep$levelCount = n;
        }
        return sleep$queueArray;
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
     * MainReactor.Server: the idle wait's deadline. 1.20.1 keeps the tick schedule in Util.getMillis() milliseconds
     * (nextTickTime, delayedTasksMaxNextTickTime; 1.21: ...Nanos), and haveTime() turns false at getMillis() >= the
     * deadline, i.e. at Util.getNanos() >= deadline * 1e6: that instant in nanoseconds.
     */
    @Override
    public long sleep$deadlineNanos() {
        return (this.mayHaveDelayedTasks ? this.delayedTasksMaxNextTickTime : this.nextTickTime) * 1_000_000L;
    }

    // 1.21.1 overwrites MinecraftServer.waitForTasks here (with its idle time logging). 1.20.1's MinecraftServer has no
    // waitForTasks of its own (BlockableEventLoop's runs) and no idle time logging: MainReactorWaitMixin takes the park.
}

