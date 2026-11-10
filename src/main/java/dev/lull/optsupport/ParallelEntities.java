package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;

/**
 * exp/parallel-entities (client, lab.opt.ParallelEntityTicksMixin): the tail of the client's entity walk ticked on the
 * frame pool (notes/67). The client ticks its entities in EntityTickList's order (insertion: new dropped items land at
 * the end); the longest tail of entities that may tick apart (not living, no vehicle or passengers, a class not refused)
 * would tick last anyway - nothing ticks after them, so nothing can see one of them in its new state too early. The head
 * ticks as vanilla does, then the tail on the workers and the render thread together, each entity exactly as vanilla
 * ticks it (the same consumer: guardEntityTick(tickNonPassenger)).
 *
 * <p>A worker's entity crossing a section boundary does not move itself in the entity storage there (not safe from
 * several threads): the move is recorded and done on the render thread after the tail, in the tail's order - the storage
 * ends as vanilla leaves it (during the tail other entities see an entity in the storage only through collisions, and a
 * tail entity is not one you can collide with... checked: one that is, refuses the tail). Collisions on the workers go
 * through their own kernels, blocks whose shape is not static are asked of the render thread (ParticleWorkers - with
 * the asking entity's context when the shape may depend on it). Anything a worker's tick does that belongs to the render
 * thread (particles, sounds, synched data, world writes) or an entity query that sees another entity of the tail is a
 * conflict: done under a lock, counted, its class refused from then on (-Dlab.pentities.check compares every parallel
 * tick with a serial one field by field: an exactness test, not a mode for play).
 */
public final class ParallelEntities {
    private ParallelEntities() {}

    public static volatile boolean enabled = true;
    private static final int MIN = Integer.getInteger("lab.pentities.min", 128);
    private static final int CHUNK = Integer.getInteger("lab.pentities.chunk", 16);
    /** classes a conflict refused (their entities tick in the head from then on) */
    private static final Set<Class<?>> REFUSED = ConcurrentHashMap.newKeySet();
    public static long walks, tails, inTail, deferredMoves, deferredRemoves, conflicts;

    private static Entity[] all = new Entity[4096];
    private static int n;
    /** the tail being ticked: [tail0, n) of all; the moves its workers deferred */
    private static int tail0;
    private static EntityInLevelCallback[] movedCb = new EntityInLevelCallback[4096], removedCb = new EntityInLevelCallback[4096];
    private static Entity.RemovalReason[] removedWhy = new Entity.RemovalReason[4096];
    private static Consumer<Entity> tick;
    private static final AtomicInteger NEXT = new AtomicInteger(), REMAINING = new AtomicInteger();
    private static volatile Throwable error;
    private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[] {-1});

    /** Replaces EntityTickList.forEach(consumer) in ClientLevel.tickEntities. */
    public static void forEach(net.minecraft.world.level.entity.EntityTickList list, Consumer<Entity> consumer) {
        if (!enabled || !CollideKernel.ON || !CollideKernel.usable() || dev.lull.optsupport.sleep.EntitySleep.CLIENT_ON || tickListeners()) {
            if (enabled && tickListeners()) skippedListeners++;
            list.forEach(consumer);
            return;
        }
        n = 0;
        list.forEach(e -> {   // the walk's order, as forEach gives it (its own iteration guard)
            if (n == all.length) all = java.util.Arrays.copyOf(all, n * 2);
            all[n++] = e;
        });
        walks++;
        long t0 = System.nanoTime();
        boolean serial = ALTERNATE && (walks & 1) == 0;
        try {
            if (serial) {
                for (int k = 0; k < n; k++) consumer.accept(all[k]);
                return;
            }
            // runs of entities that may tick apart, each on the pool where long enough; everything else in its slot
            int i = 0;
            while (i < n) {
                if (!mayTickApart(all[i])) {
                    consumer.accept(all[i++]);
                    continue;
                }
                int j = i;
                while (j < n && mayTickApart(all[j])) j++;
                if (j - i >= MIN) runTail(i, j, consumer);
                else {
                    skippedShort++;
                    if (ALTERNATE) BREAKERS.merge((j - i < 8 ? "<8" : j - i < 32 ? "<32" : "<" + MIN) + (j < n ? " by " + why(all[j]) : " at the end"), 1L, Long::sum);
                    for (int k = i; k < j; k++) consumer.accept(all[k]);
                }
                i = j;
            }
        } finally {
            java.util.Arrays.fill(all, 0, n, null);
            if (ALTERNATE) {
                long dt = System.nanoTime() - t0;
                if (serial) { nsSerial += dt; walksSerial++; } else { nsParallel += dt; walksParallel++; }
            }
        }
    }

    /** test: every run's parallel ticks compared with vanilla ones (ParallelEntitiesCheck) */
    private static final boolean CHECK = Boolean.getBoolean("lab.pentities.check");

    /** test: every other walk ticks all serially - the two modes' walk times in one run, under the same load */
    private static final boolean ALTERNATE = Boolean.getBoolean("lab.pentities.alternate");
    private static long nsSerial, nsParallel, walksSerial, walksParallel;

    /** what ended a short tail: the class and the reason of the entity before it */
    private static final java.util.Map<String, Long> BREAKERS = new java.util.concurrent.ConcurrentHashMap<>();

    private static String why(Entity e) {
        return e.getClass().getSimpleName() + (e instanceof LivingEntity ? " (living)" : e.isPassenger() ? " (passenger)" : e.isVehicle() ? " (vehicle)"
                : e.canBeCollidedWith() ? " (collidable)" : REFUSED.contains(e.getClass()) ? " (refused)" : "");
    }

    private static boolean mayTickApart(Entity e) {
        return !(e instanceof LivingEntity) && !e.isPassenger() && !e.isVehicle() && !e.canBeCollidedWith() && !REFUSED.contains(e.getClass());
    }

    public static long skippedListeners, skippedShort;

    /**
     * Forge 1.20.1 posts no event around a non-living entity's tick (LivingTickEvent: living entities only, never in a
     * run): no listener can act on a run entity (NeoForge's EntityTickEvent is analysed on 1.21.1)
     */
    private static boolean tickListeners() {
        return false;
    }

    // ------------------------------------------------------------------ the tail

    /** the run's end (exclusive) */
    private static int end;

    private static void runTail(int t, int runEnd, Consumer<Entity> consumer) {
        tails++;
        inTail += runEnd - t;
        tail0 = t;
        end = runEnd;
        tick = consumer;
        if (movedCb.length < n) {
            int len = Math.max(n, movedCb.length * 2);
            movedCb = new EntityInLevelCallback[len];
            removedCb = new EntityInLevelCallback[len];
            removedWhy = new Entity.RemovalReason[len];
        }
        error = null;
        Object[][] before = CHECK ? ParallelEntitiesCheck.snapshot(all, t, runEnd) : null;
        NEXT.set(t);
        REMAINING.set(runEnd - t);
        ParticleWorkers.renderThread = Thread.currentThread();
        levelRandom = all[t].level().random;
        ParticleWorkers.SHARED.clear();
        ParticleWorkers.active = true;
        try {
            LabRuntime pool = FrameReactor.pool();
            int chunks = (runEnd - t + CHUNK - 1) / CHUNK;
            int helpers = Math.min(pool.workerCount(), chunks - 1);
            for (int k = 0; k < helpers; k++) pool.execute(ParallelEntities::workerRun);
            drain(true);
            long spinUntil = System.nanoTime() + 20_000;
            while (REMAINING.get() != 0) {
                ParticleWorkers.serve();
                if (REMAINING.get() == 0) break;
                if (System.nanoTime() < spinUntil) Thread.onSpinWait();
                else LockSupport.park(ParallelEntities.class);
            }
            ParticleWorkers.serve();
        } finally {
            ParticleWorkers.active = false;
        }
        // the deferred section moves and removals, in the run's order (each entity's last position decides its section;
        // a removal after it: vanilla's callback is NULL after onRemove, later moves of a removed entity change nothing)
        for (int i = t; i < runEnd; i++) {
            EntityInLevelCallback cb = movedCb[i];
            if (cb != null) {
                movedCb[i] = null;
                deferredMoves++;
                if (!CHECK) cb.onMove();
            }
            cb = removedCb[i];
            if (cb != null) {
                removedCb[i] = null;
                deferredRemoves++;
                if (!CHECK) cb.onRemove(removedWhy[i]);
                removedWhy[i] = null;
            }
        }
        Throwable e = error;
        if (CHECK && e == null) ParallelEntitiesCheck.verify(all, t, runEnd, consumer, before);
        if (e instanceof RuntimeException re) throw re;
        if (e instanceof Error er) throw er;
    }

    private static void workerRun() {
        boolean[] in = ParticleWorkers.IN_WORKER.get();
        in[0] = true;
        try {
            drain(false);
        } finally {
            in[0] = false;
        }
    }

    private static void drain(boolean render) {
        int done = 0;
        int[] cur = CURRENT.get();
        for (int s; (s = NEXT.getAndAdd(CHUNK)) < end; ) {
            int e = Math.min(end, s + CHUNK);
            for (int j = s; j < e; j++) {
                cur[0] = j;
                try {
                    tick.accept(all[j]);
                } catch (Throwable x) {
                    error = x;
                }
                if (render && ParticleWorkers.pending()) ParticleWorkers.serve();
            }
            done += e - s;
        }
        cur[0] = -1;
        if (done > 0 && REMAINING.addAndGet(-done) == 0 && !render) LockSupport.unpark(ParticleWorkers.renderThread);
    }

    // ------------------------------------------------------------------ hooks

    /** on a worker of the tail (or the render thread ticking a tail entity): its index in the walk, else -1 */
    public static int tailIndex() {
        return ParticleWorkers.active ? CURRENT.get()[0] : -1;
    }

    /**
     * Entity.setPosRaw's levelCallback.onMove() for an entity of the tail (on a worker or the render thread alike: the
     * storage then gets the tail's moves in the tail's order): recorded, done after the tail (true = taken)
     */
    public static boolean deferMove(EntityInLevelCallback cb) {
        if (!ParticleWorkers.active) return false;
        int i = CURRENT.get()[0];
        if (i < 0) return false;
        movedCb[i] = cb;
        return true;
    }

    /** Entity.setRemoved's levelCallback.onRemove(reason) for a run entity: recorded, done after the run (true = taken) */
    public static boolean deferRemove(EntityInLevelCallback cb, Entity.RemovalReason reason) {
        if (!ParticleWorkers.active) return false;
        int i = CURRENT.get()[0];
        if (i < 0) return false;
        removedCb[i] = cb;
        removedWhy[i] = reason;
        return true;
    }

    /** the level's random source while a run ticks */
    private static Object levelRandom;

    /**
     * a draw from a random source while a run ticks: from the level's, by a run entity (a worker or the render thread),
     * the draws would come out of the walk's order - a conflict (counted, the class refused)
     */
    public static void draw(Object random) {
        if (random != levelRandom) return;
        int i = CURRENT.get()[0];
        if (i < 0) return;
        Entity e = all[i];
        conflicts++;
        if (e != null && REFUSED.add(e.getClass()))
            System.out.println("[sleep/opt] parallel entities: " + e.getClass().getName() + " refused (a draw from the level's random source)");
    }

    /** a worker did something that belongs to the render thread: counted, its class refused from the next walk on */
    public static void conflict(String what) {
        int i = CURRENT.get()[0];
        if (i < 0 || !ParticleWorkers.inWorker()) return;
        Entity e = all[i];
        conflicts++;
        if (e != null && REFUSED.add(e.getClass()))
            System.out.println("[sleep/opt] parallel entities: " + e.getClass().getName() + " refused (" + what + " on a worker)");
    }

    /** an entity query on a worker: its result must not hold another entity of the tail (still being ticked) */
    public static void query(java.util.List<?> result) {
        int i = CURRENT.get()[0];
        if (i < 0 || !ParticleWorkers.inWorker()) return;
        Entity self = all[i];
        for (Object o : result)
            if (o != self && o instanceof Entity e && indexInTail(e)) {
                conflict("an entity query that saw another entity of the tail");
                return;
            }
    }

    private static boolean indexInTail(Entity e) {
        for (int k = tail0; k < end; k++) if (all[k] == e) return true;
        return false;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "parallel entities: walks %d, tails %d (%d entities), skipped: listeners %d, short tail %d; deferred section moves %d, removals %d, conflicts %d (classes refused %d)",
                walks, tails, inTail, skippedListeners, skippedShort, deferredMoves, deferredRemoves, conflicts, REFUSED.size()) + " tail ended by " + BREAKERS
                + (CHECK ? ParallelEntitiesCheck.report() : "")
                + (ALTERNATE ? String.format(java.util.Locale.ROOT, "; alternate walks: serial %.3f ms (%d), parallel %.3f ms (%d)",
                nsSerial / 1e6 / Math.max(1, walksSerial), walksSerial, nsParallel / 1e6 / Math.max(1, walksParallel), walksParallel) : "");
    }
}
