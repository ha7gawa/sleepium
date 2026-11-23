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
            if (TickAsync.ON && !serial) asyncSplit(consumer);   // (the left-out ones out of all[]; the rest walks as usual below)
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

    /**
     * -Dlab.pentities.living=true (plan B5, 11-15): living entities too (mobs - their client tick is lerping toward the
     * server's positions, head turns, walk animation, effects), players never (the local one is driven by input). For a
     * living entity on a worker any entity query that sees another entity is a conflict (pushEntities on the client
     * pushes the players it finds: a write to another entity).
     */
    static final boolean LIVING = Boolean.getBoolean("lab.pentities.living");
    /** test: the tail ticked by the render thread alone (the check's own artefacts apart from the threads') */
    private static final boolean SERIAL_TAIL = Boolean.getBoolean("lab.pentities.serialTailTest");

    private static boolean mayTickApart(Entity e) {
        if (LIVING && e instanceof LivingEntity)
            return !(e instanceof net.minecraft.world.entity.player.Player) && !livingTickListened() && !e.isPassenger() && !e.isVehicle() && !e.canBeCollidedWith() && !REFUSED.contains(e.getClass());
        return !(e instanceof LivingEntity) && !e.isPassenger() && !e.isVehicle() && !e.canBeCollidedWith() && !REFUSED.contains(e.getClass());
    }

    public static long skippedListeners, skippedShort;

    /**
     * Forge 1.20.1 posts no event around a non-living entity's tick (LivingTickEvent: living entities only, never in a
     * run): no listener can act on a run entity (NeoForge's EntityTickEvent is analysed on 1.21.1)
     */
    /** ServerParallelItems: an EntityTickEvent listener would act on a dropped item (same analysis as the client's runs) */
    static boolean tickListenersActing() {
        return tickListeners();
    }

    private static boolean tickListeners() {
        return false;
    }

    /**
     * 1.20.1: a living entity's tick posts Forge's LivingTickEvent (LivingEntity.tick's first line) - with listeners a
     * living entity ticked on a worker would run a mod's code there: living entities stay on the main thread then (a bus
     * not ours: unknown, the same)
     */
    private static boolean livingTickListened() {
        var bus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;
        return !(bus instanceof CompositeEventBus cb) || cb.hasListeners(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent.class);
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
        levelRandom = FrameRandom.unwrap(all[t].level().random);
        ParticleWorkers.SHARED.clear();
        ParticleWorkers.active = true;
        try {
            LabRuntime pool = FrameReactor.pool();
            int chunks = (runEnd - t + CHUNK - 1) / CHUNK;
            int helpers = SERIAL_TAIL ? 0 : Math.min(pool.workerCount(), chunks - 1);
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

    // ------------------------------------------------------------------ TickAsync: the walk next to the frame

    private static int asyncM;
    private static volatile boolean asyncRunning;
    private static TickPoses.Pair[] asyncPairs = new TickPoses.Pair[256];
    /** per walking entity: 1 once its tick (and capture) is done - a frame that meets an unseen one waits for that */
    private static java.util.concurrent.atomic.AtomicIntegerArray asyncDone = new java.util.concurrent.atomic.AtomicIntegerArray(256);

    /** render thread, a frame: wait (serving the workers) until walking entity i has ticked */
    static void awaitTicked(int i) {
        long spinUntil = System.nanoTime() + 20_000;
        while (asyncRunning && asyncDone.get(i) == 0) {
            ParticleWorkers.serve();
            if (System.nanoTime() < spinUntil) Thread.onSpinWait();
            else LockSupport.parkNanos(50_000L);
        }
    }

    static boolean asyncRunning() {
        return asyncRunning;
    }

    /** the walk's entities all ticked (a join would not wait) */
    static boolean asyncFinished() {
        return REMAINING.get() == 0;
    }

    /**
     * The walk with TickAsync: entities drawn from tick poses left out (kept at the front of all[], in the walk's order,
     * each with its spare pair), every other one ticked here in its slot. False: none left out - the usual walk.
     */
    private static Entity[] asyncAll = new Entity[256];
    private static ParallelRender.Job[] asyncSnaps = new ParallelRender.Job[256];
    /** snapshot jobs, reused (render thread hands them out at the split) */
    private static final java.util.ArrayList<ParallelRender.Job> SNAP_POOL_A = new java.util.ArrayList<>(), SNAP_POOL_B = new java.util.ArrayList<>();
    /** the walks split so far: their snapshot jobs come from the two pools in turn (a walk's jobs, let go at its join, are
     * reused by the walk after next - past the generation gate, TickAsync.gate) */
    private static long splits;
    /** TickAsync generations: the joined walk's snapshot jobs, let go at the gate (a frame begun before the join may draw them) */
    private static final java.util.ArrayList<ParallelRender.Job> RETIRED = new java.util.ArrayList<>();

    private static boolean asyncSplit(Consumer<Entity> consumer) {
        if (asyncPairs.length < n) asyncPairs = new TickPoses.Pair[Math.max(n, asyncPairs.length * 2)];
        if (asyncAll.length < n) asyncAll = new Entity[Math.max(n, asyncAll.length * 2)];
        if (asyncSnaps.length < n) asyncSnaps = new ParallelRender.Job[Math.max(n, asyncSnaps.length * 2)];
        int m = 0, r = 0, snaps = 0;
        java.util.ArrayList<ParallelRender.Job> SNAP_POOL = (splits & 1) == 0 ? SNAP_POOL_A : SNAP_POOL_B;
        for (int k = 0; k < n; k++) {
            Entity e = all[k];
            if (mayTickApart(e) && TickAsync.eligible(e)) {
                asyncPairs[m] = TickPoses.spare(e);
                asyncSnaps[m] = null;
                asyncAll[m++] = e;
                TickAsync.WALKING.put(e, Boolean.TRUE);
            } else if (mayTickApart(e) && TickAsync.snapEligible(e)) {
                if (SNAP_POOL.size() <= snaps) SNAP_POOL.add(new ParallelRender.Job());
                ParallelRender.Job sj = SNAP_POOL.get(snaps++);
                ParallelRender.snapshotJob(sj, e);
                asyncPairs[m] = null;
                asyncSnaps[m] = sj;
                asyncAll[m++] = e;
                TickAsync.WALKING.put(e, sj);
                TickAsync.snapshots++;
            } else if (mayTickApart(e) && TickAsync.unseenEligible(e)) {
                asyncPairs[m] = null;
                asyncSnaps[m] = null;
                asyncAll[m++] = e;
                TickAsync.WALKING.put(e, Integer.valueOf(m - 1));   // (nothing to draw it from: a frame that meets it waits for its tick)
                TickAsync.unseen++;
            } else all[r++] = e;   // (r <= k: that slot is done with)
        }
        if (m == 0) return false;
        splits++;
        java.util.Arrays.fill(all, r, n, null);
        n = r;   // the rest walks as usual (its runs on the pool, the others in their slots)
        asyncM = m;
        asyncTick = consumer;
        return true;
    }

    private static Consumer<Entity> asyncTick;

    /** Minecraft.tick returned: the left-out entities on the pool, each ticked then captured; the render thread goes on */
    static void startAsync() {
        int helpers = prepareAsync();
        if (helpers == 0) return;
        LabRuntime pool = FrameReactor.pool();
        for (int k = 0; k < helpers; k++) pool.execute(ParallelEntities::asyncWorkerRun);
    }

    /**
     * the walk's state set up for its workers (TickAsync's graph dispatches them as its walk nodes); the workers it wants
     * (0: no walk this tick)
     */
    static int prepareAsync() {
        int m = asyncM;
        if (m == 0 || asyncRunning) return 0;
        if (all.length < m) all = java.util.Arrays.copyOf(all, Math.max(m, all.length * 2));
        System.arraycopy(asyncAll, 0, all, 0, m);   // (the walk is over: all[] is free; the hooks index it)
        java.util.Arrays.fill(asyncAll, 0, m, null);
        tick = asyncTick;
        if (asyncDone.length() < m) asyncDone = new java.util.concurrent.atomic.AtomicIntegerArray(Math.max(m, asyncDone.length() * 2));
        for (int i = 0; i < m; i++) asyncDone.set(i, 0);
        tail0 = 0;
        end = m;
        if (movedCb.length < m) {
            int len = Math.max(m, movedCb.length * 2);
            movedCb = new EntityInLevelCallback[len];
            removedCb = new EntityInLevelCallback[len];
            removedWhy = new Entity.RemovalReason[len];
        }
        error = null;
        NEXT.set(0);
        REMAINING.set(m);
        ParticleWorkers.renderThread = Thread.currentThread();
        levelRandom = FrameRandom.unwrap(all[0].level().random);
        ParticleWorkers.SHARED.clear();
        ParticleWorkers.active = true;
        asyncRunning = true;
        TickAsync.walks++;
        TickAsync.entities += m;
        LabRuntime pool = FrameReactor.pool();
        int helpers = Math.max(1, Math.min(pool.workerCount(), m));
        return helpers;
    }

    /** the main thread before it waits for the walk: the entities no worker claimed yet, ticked here (serving the workers) */
    static void helpAsync() {
        if (asyncRunning) asyncDrain(true);
    }

    /** a walk node's body: the claims of a worker (a node with nothing left to claim ends at once) */
    static void asyncWorkerRun() {
        boolean[] in = ParticleWorkers.IN_WORKER.get();
        in[0] = true;
        try {
            asyncDrain(false);
        } finally {
            in[0] = false;
        }
    }

    /** claims of the left-out entities: each ticked (as the walk would), then its spare pair captured on this thread */
    private static void asyncDrain(boolean render) {
        int done = 0;
        int[] cur = CURRENT.get();
        for (int s0; (s0 = NEXT.getAndAdd(CHUNK)) < end; ) {   // (1.20.1: fixed chunks)
            int e0 = Math.min(end, s0 + CHUNK);
            for (int j = s0; j < e0; j++) {
                ParallelRender.Job sj = asyncSnaps[j];
                if (sj != null) {   // the snapshot before the tick (a frame may have taken it: then wait for it)
                    sj.run();
                    sj.await();
                }
                cur[0] = j;
                try {
                    tick.accept(all[j]);
                } catch (Throwable x) {
                    error = x;
                }
                cur[0] = -1;   // (the capture renders: not the tick's doing for the conflict hooks)
                try {
                    if (!all[j].isRemoved()) TickPoses.captureInto(asyncPairs[j], all[j]);
                } catch (Throwable x) {
                    if (asyncPairs[j] != null) asyncPairs[j].markUnusable();
                }
                asyncDone.set(j, 1);
                if (render && ParticleWorkers.pending()) ParticleWorkers.serve();
            }
            done += e0 - s0;
        }
        cur[0] = -1;
        if (done > 0 && REMAINING.addAndGet(-done) == 0 && !render) { LockSupport.unpark(ParticleWorkers.renderThread); FrameThread.walkDone(); }
    }

    /** render thread: the walk finished (helping first), its deferred moves and removals applied, the pairs swapped */
    static void joinAsync() {
        joinAsyncWorld();
        joinAsyncRender();
    }

    /**
     * the join's first part, the main thread's own state: the walk finished (helping), the deferred section moves and
     * removals applied - nothing a drawing frame reads (the frame thread draws meanwhile: FrameThread)
     */
    static void joinAsyncWorld() {
        try {
            asyncDrain(true);
            long spinUntil = System.nanoTime() + 20_000;
            while (REMAINING.get() != 0) {
                ParticleWorkers.serve();
                if (REMAINING.get() == 0) break;
                if (System.nanoTime() < spinUntil) Thread.onSpinWait();
                else LockSupport.parkNanos(ParallelEntities.class, 200_000L);
            }
            ParticleWorkers.serve();
        } finally {
            ParticleWorkers.active = false;
        }
        int m = asyncM;
        for (int i = 0; i < m; i++) {
            EntityInLevelCallback cb = movedCb[i];
            if (cb != null) {
                movedCb[i] = null;
                deferredMoves++;
                cb.onMove();
            }
            cb = removedCb[i];
            if (cb != null) {
                removedCb[i] = null;
                deferredRemoves++;
                cb.onRemove(removedWhy[i]);
                removedWhy[i] = null;
            }
        }
    }

    /** the join's second part, what frames read: the pairs swapped, the snapshot jobs let go, the walking set emptied */
    static void joinAsyncRender() {
        joinAsyncRender(false);
    }

    /** retire: the snapshot jobs are let go later (releaseRetired, past the generation gate), not here */
    static void joinAsyncRender(boolean retire) {
        int m = asyncM;
        for (int i = 0; i < m; i++) {
            TickPoses.install(all[i], asyncPairs[i]);
            asyncPairs[i] = null;
            if (asyncSnaps[i] != null) {
                if (retire) RETIRED.add(asyncSnaps[i]);
                else ParallelRender.snapshotDone(asyncSnaps[i]);
                asyncSnaps[i] = null;
            }
        }
        java.util.Arrays.fill(all, 0, m, null);
        asyncM = 0;
        TickAsync.WALKING.clear();
        asyncRunning = false;
        Throwable e = error;
        if (e instanceof RuntimeException re) throw re;
        if (e instanceof Error er) throw er;
    }

    /** the generation gate passed (no frame begun before the join draws): the retired snapshot jobs let go */
    static void releaseRetired() {
        for (int i = 0, n = RETIRED.size(); i < n; i++) ParallelRender.snapshotDone(RETIRED.get(i));
        RETIRED.clear();
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
            org.slf4j.LoggerFactory.getLogger("sleep").info("[sleep/opt] parallel entities: " + e.getClass().getName() + " refused (a draw from the level's random source)");
    }

    /** a worker did something that belongs to the render thread: counted, its class refused from the next walk on */
    public static void conflict(String what) {
        int i = CURRENT.get()[0];
        if (i < 0 || !ParticleWorkers.inWorker()) return;
        Entity e = all[i];
        conflicts++;
        if (e != null && REFUSED.add(e.getClass()))
            org.slf4j.LoggerFactory.getLogger("sleep").info("[sleep/opt] parallel entities: " + e.getClass().getName() + " refused (" + what + " on a worker)");
    }

    /** an entity query on a worker: its result must not hold another entity of the tail (still being ticked) */
    public static void query(java.util.List<?> result) {
        int i = CURRENT.get()[0];
        if (i < 0 || !ParticleWorkers.inWorker()) return;
        Entity self = all[i];
        if (LIVING && self instanceof LivingEntity) {
            for (Object o : result)
                if (o != self && o instanceof Entity) {
                    conflict("an entity query of a living entity that saw another entity");
                    return;
                }
            return;
        }
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
