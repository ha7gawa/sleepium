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

    /** a position's shape for the tick; used: a worker took it (or asked for it) - next tick's demand */
    static final class Cell {
        final BlockState state;
        final VoxelShape moved;
        volatile boolean used;

        Cell(BlockState state, VoxelShape moved, boolean used) {
            this.state = state;
            this.moved = moved;
            this.used = used;
        }
    }

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
        if (c != null && c.state == state) {
            if (!c.used) c.used = true;
            return c.moved;
        }
        Req r = new Req(state, level, x, y, z);
        long w0 = WAIT_LOG ? System.nanoTime() : 0;
        REQUESTS.offer(r);
        LockSupport.unpark(renderThread);
        VoxelShape s;
        while ((s = r.result) == null) LockSupport.park(r);
        if (WAIT_LOG) WAITED.addAndGet(System.nanoTime() - w0);
        return s;
    }

    /** The render thread's kernel computed one (the particle window): the workers may take it too. */
    static void offer(BlockState state, int x, int y, int z, VoxelShape moved) {
        if (active) SHARED.put(BlockPos.asLong(x, y, z), new Cell(state, moved, false));
    }

    /** Render thread: answer the workers' questions. */
    /**
     * The positions whose shapes the workers took or asked for in the last tick: at the next tick's start the render
     * thread computes them again - in that tick's level, as it would answer the questions - while the workers start,
     * so they mostly find the shared memo filled instead of waiting.
     */
    private static final java.util.Map<Object, it.unimi.dsi.fastutil.longs.LongArrayList> DEMANDS = new java.util.IdentityHashMap<>();

    /** Render thread, at the end of a list's parallel tick: what its workers used (the list's demand for its next tick). */
    static void keepDemand(Object list) {
        it.unimi.dsi.fastutil.longs.LongArrayList d = DEMANDS.computeIfAbsent(list, k -> new it.unimi.dsi.fastutil.longs.LongArrayList());
        d.clear();
        for (var e : SHARED.entrySet()) if (e.getValue().used) d.add(e.getKey().longValue());
    }
    public static long prefilled;
    static final boolean PREFILL = !"false".equals(System.getProperty("lab.particles.prefill"));

    /** Render thread, the tick's start (workers launched): last ticks' positions computed again, questions answered between. */
    static void prefill(Level level, Object list) {
        if (!PREFILL) return;
        it.unimi.dsi.fastutil.longs.LongArrayList DEMAND = DEMANDS.get(list);
        if (DEMAND == null) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < DEMAND.size(); i++) {
            long key = DEMAND.getLong(i);
            if (SHARED.containsKey(key)) continue;
            pos.set(key);
            BlockState state = level.getBlockState(pos);
            if (state instanceof BlockCollisionMemo.StaticShape st && st.sleep$staticCollision() != null) continue;   // workers take static shapes themselves
            VoxelShape sh = state.getCollisionShape(level, pos, CollisionContext.empty());
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            VoxelShape moved = sh == Shapes.block() || sh.isEmpty() ? sh : sh.move((double) x, (double) y, (double) z);
            SHARED.put(key, new Cell(state, moved, false));
            prefilled++;
            if (pending()) serve();   // a waiting worker answered between the prefilled shapes
        }
        serve();
    }

    /** the burst log's measure: the workers' time waiting for the render thread's answers, summed */
    static final boolean WAIT_LOG = Boolean.getBoolean("lab.particles.burstLog");
    static final java.util.concurrent.atomic.AtomicLong WAITED = new java.util.concurrent.atomic.AtomicLong();

    /** a worker waits for an answer (one volatile read: the render thread checks it between its own particles) */
    static boolean pending() {
        return !REQUESTS.isEmpty();
    }

    static void serve() {
        Req r;
        while ((r = REQUESTS.poll()) != null) {
            Cell c = SHARED.get(BlockPos.asLong(r.x, r.y, r.z));
            if (c != null && c.state == r.state) {   // asked by another worker too, or prefilled meanwhile: this tick's answer
                if (!c.used) c.used = true;
                r.result = c.moved;
                LockSupport.unpark(r.waiter);
                continue;
            }
            BlockPos pos = new BlockPos(r.x, r.y, r.z);
            VoxelShape sh = r.state.getCollisionShape(r.level, pos, CollisionContext.empty());
            VoxelShape moved = sh == Shapes.block() || sh.isEmpty() ? sh : sh.move((double) r.x, (double) r.y, (double) r.z);
            SHARED.put(pos.asLong(), new Cell(r.state, moved, true));
            requests++;
            r.result = moved;
            LockSupport.unpark(r.waiter);
        }
    }
}
