package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * ParallelRender: the drain an entity was last queued in and last drawn on a worker in, on the entity (11-17: two
 * ConcurrentHashMap puts with a boxed Long per entity per frame - the horde's ~50 us a frame, with the map's sweep).
 * Stored as drain + 1 (0: never); volatile - the main thread's split reads them.
 */
@Mixin(Entity.class)
public abstract class EntityDrawFramesMixin implements dev.lull.optsupport.ParallelRender.DrawFrames {
    @Unique private volatile long sleep$queuedIn, sleep$workerIn;

    @Override
    public long sleep$queuedIn() {
        return sleep$queuedIn;
    }

    @Override
    public void sleep$setQueuedIn(long f) {
        sleep$queuedIn = f;
    }

    @Override
    public long sleep$workerIn() {
        return sleep$workerIn;
    }

    @Override
    public void sleep$setWorkerIn(long f) {
        sleep$workerIn = f;
    }
}
