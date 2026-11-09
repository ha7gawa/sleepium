package dev.lull.optsupport;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * ParallelParticles' side the common collision code sees (no client classes: dedicated servers load it too): whether
 * this thread is a worker of a parallel particle tick, and the non-static block shapes such workers need - computed by
 * the render thread only (mod code is not ours to run on other threads): this tick's shared memo, or a question the
 * render thread answers between its own chunks and while it waits for the workers.
 */
public final class ParticleWorkers {
    private ParticleWorkers() {}

    static volatile boolean active;
    static Thread renderThread;
    static final ThreadLocal<boolean[]> IN_WORKER = ThreadLocal.withInitial(() -> new boolean[1]);

    /** a worker of a parallel particle tick (its collide kernel and the shared shape memo) */
    public static boolean inWorker() {
        return active && IN_WORKER.get()[0];
    }

    record Cell(BlockState state, VoxelShape moved) {}

    static final ConcurrentHashMap<Long, Cell> SHARED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Req> REQUESTS = new ConcurrentLinkedQueue<>();
    public static long requests;

    private static final class Req {
        final BlockState state;
        final Level level;
        final int x, y, z;
        final Thread waiter = Thread.currentThread();
        volatile VoxelShape result;

        Req(BlockState state, Level level, int x, int y, int z) {
            this.state = state;
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A worker: the non-static shape of this state at (x, y, z), moved to its cell, as the render thread computes it. */
    static VoxelShape shape(BlockState state, Level level, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Cell c = SHARED.get(key);
        if (c != null && c.state == state) return c.moved;
        Req r = new Req(state, level, x, y, z);
        REQUESTS.offer(r);
        LockSupport.unpark(renderThread);
        VoxelShape s;
        while ((s = r.result) == null) LockSupport.park(r);
        return s;
    }

    /** The render thread's kernel computed one (the particle window): the workers may take it too. */
    static void offer(BlockState state, int x, int y, int z, VoxelShape moved) {
        if (active) SHARED.put(BlockPos.asLong(x, y, z), new Cell(state, moved));
    }

    /** Render thread: answer the workers' questions. */
    static void serve() {
        Req r;
        while ((r = REQUESTS.poll()) != null) {
            BlockPos pos = new BlockPos(r.x, r.y, r.z);
            VoxelShape sh = r.state.getCollisionShape(r.level, pos, CollisionContext.empty());
            VoxelShape moved = sh == Shapes.block() || sh.isEmpty() ? sh : sh.move((double) r.x, (double) r.y, (double) r.z);
            SHARED.put(pos.asLong(), new Cell(r.state, moved));
            requests++;
            r.result = moved;
            LockSupport.unpark(r.waiter);
        }
    }
}
