package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * lab (notes/99, 1.3.6; -Dlab.opt.ServerParallelItemsMixin=true -Dlab.spec=true): runs of farm animals in the server's
 * entity walk ticked on the server pool. Stage 0 - the UPPER BOUND, not exact: ticks run in parallel with no conflict
 * handling (a push into a neighbour ticking at the same time races, a query sees neighbours before or after their tick).
 * What is already as the exact design will have it: the entity storage's moves and removals and the tick's effects
 * (sounds, game events, particles, entity events) are deferred and done after the run in each entity's order; chunk
 * lookups from workers read the published chunk map; vanilla's shared path type cache is read-only during the run, each
 * thread computes into an overlay merged after the run. {@code -Dlab.spec.alternate=true}: every other walk serial, the
 * two walk times reported ("[sleep/spec]").
 */
public final class SpecTicks {
    private SpecTicks() {}

    public static final boolean ON = Boolean.getBoolean("lab.spec");
    static final int MIN = Integer.getInteger("lab.spec.min", 64);
    static final int CHUNK = Integer.getInteger("lab.spec.chunk", 16);
    static final boolean ALTERNATE = Boolean.getBoolean("lab.spec.alternate");

    private static volatile boolean running;
    private static Thread main;
    private static Entity[] all = new Entity[4096];
    private static int n;
    private static final ThreadLocal<int[]> CUR = ThreadLocal.withInitial(() -> new int[] {-1});
    /** per walk index: what its tick deferred, in its order - an EntityInLevelCallback (a storage move), a Removal, a Runnable (an effect) */
    private static Object[][] deferred = new Object[4096][];
    private static int[] deferredN = new int[4096];
    static long walks, runs, inRuns, deferredMoves, deferredRemoves, deferredFx, chunkMisses, pathOverlay, nsSerial, nsParallel, walksSerial, walksParallel;

    record Removal(EntityInLevelCallback cb, Entity.RemovalReason why) {}

    public static void forEach(net.minecraft.world.level.entity.EntityTickList list, Consumer<Entity> consumer, ServerLevel lvl) {
        if (ParallelEntities.tickListenersActing()) {   // (an active profiler is fine: workers get the inactive one)
            list.forEach(consumer);
            return;
        }
        n = 0;
        list.forEach(e -> {
            if (n == all.length) all = java.util.Arrays.copyOf(all, n * 2);
            all[n++] = e;
        });
        walks++;
        boolean serial = ALTERNATE && (walks & 1) == 0;
        long t0 = System.nanoTime();
        try {
            int i = 0;
            while (i < n) {
                if (serial || !eligible(all[i])) {
                    consumer.accept(all[i++]);
                    continue;
                }
                int j = i;
                while (j < n && eligible(all[j])) j++;
                if (j - i < MIN) for (int k = i; k < j; k++) consumer.accept(all[k]);
                else run(i, j, consumer, lvl);
                i = j;
            }
        } finally {
            long dt = System.nanoTime() - t0;
            if (serial) { nsSerial += dt; walksSerial++; } else { nsParallel += dt; walksParallel++; }
            java.util.Arrays.fill(all, 0, n, null);
            if (walks % 40 == 0) report();
        }
    }

    static boolean eligible(Entity e) {
        return e instanceof Animal && !e.isRemoved() && !e.isPassenger() && !e.isVehicle() && e.getClass().getName().startsWith("net.minecraft.");
    }

    private static void run(int from, int to, Consumer<Entity> consumer, ServerLevel lvl) {
        if (deferred.length < to) {
            int len = Math.max(to, deferred.length * 2);
            deferred = java.util.Arrays.copyOf(deferred, len);
            deferredN = java.util.Arrays.copyOf(deferredN, len);
        }
        runs++;
        inRuns += to - from;
        main = Thread.currentThread();
        pathCache = lvl.getPathTypeCache();
        var pool = ServerReactor.pool();
        long t0 = System.nanoTime();
        running = true;
        try {
            pool.forRange(to - from, CHUNK, pool.workerCount(), q -> {
                int k = from + q;
                int[] cur = CUR.get();
                cur[0] = k;
                try {
                    consumer.accept(all[k]);
                } finally {
                    cur[0] = -1;
                }
            });
        } finally {
            running = false;
        }
        long t1 = System.nanoTime();
        nsPhase += t1 - t0;
        mergePaths();
        // what the ticks deferred, in the walk's order
        for (int k = from; k < to; k++) {
            Object[] d = deferred[k];
            int m = deferredN[k];
            for (int q = 0; q < m; q++) {
                Object o = d[q];
                d[q] = null;
                if (o instanceof EntityInLevelCallback cb) {
                    deferredMoves++;
                    cb.onMove();
                } else if (o instanceof Removal r) {
                    deferredRemoves++;
                    r.cb.onRemove(r.why);
                } else {
                    deferredFx++;
                    ((Runnable) o).run();
                }
            }
            deferredN[k] = 0;
        }
        nsCommit += System.nanoTime() - t1;
    }

    static long nsPhase, nsCommit;
    private static long lastPhase, lastCommit, lastPar, lastWalksPar, lastRuns;

    private static void add(int k, Object o) {
        Object[] d = deferred[k];
        int m = deferredN[k];
        if (d == null) deferred[k] = d = new Object[4];
        else if (m == d.length) deferred[k] = d = java.util.Arrays.copyOf(d, m * 2);
        d[m] = o;
        deferredN[k] = m + 1;
    }

    /** the walk index this thread is ticking in a run, or -1 */
    private static int current() {
        return running ? CUR.get()[0] : -1;
    }

    // ------------------------------------------------------------------ hooks

    public static boolean running() {
        return running;
    }

    public static boolean onWorker() {
        return running && Thread.currentThread() != main;
    }

    /** Entity.setPosRaw's levelCallback.onMove() during a run: kept for after it (a repeat of the last one dropped) */
    public static boolean deferMove(EntityInLevelCallback cb) {
        int k = current();
        if (k < 0) return false;
        int m = deferredN[k];
        if (m == 0 || deferred[k][m - 1] != cb) add(k, cb);
        return true;
    }

    public static boolean deferRemove(EntityInLevelCallback cb, Entity.RemovalReason why) {
        int k = current();
        if (k < 0) return false;
        add(k, new Removal(cb, why));
        return true;
    }

    /** an effect of a run tick (a sound, game event, particles, entity / level / damage event): done after the run */
    public static boolean deferFx(Runnable r) {
        int k = current();
        if (k < 0) return false;
        add(k, r);
        return true;
    }

    /** a worker's chunk lookup during a run: the published chunk map, no blocking, no shared lookup cache */
    public static LevelChunk chunk(ServerChunkCache cache, int x, int z, boolean require) {
        ChunkHolder h = cache.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        ChunkAccess c = h == null ? null : h.getChunkIfPresent(ChunkStatus.FULL);
        if (c instanceof LevelChunk lc) return lc;
        chunkMisses++;
        if (require) throw new IllegalStateException("[sleep/spec] a run tick needs chunk " + x + ", " + z + " that is not loaded");
        return null;
    }

    // ------------------------------------------------------------------ vanilla's path type cache: read-only in a run, overlays

    private static net.minecraft.world.level.pathfinder.PathTypeCache pathCache;
    private static final List<Long2ObjectOpenHashMap<PathType>> OVERLAYS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final ThreadLocal<Long2ObjectOpenHashMap<PathType>> OVERLAY = ThreadLocal.withInitial(() -> {
        Long2ObjectOpenHashMap<PathType> m = new Long2ObjectOpenHashMap<>();
        OVERLAYS.add(m);
        return m;
    });

    /** PathTypeCache.getOrCompute during a run (any thread): this thread's computes, else the shared entry, else computed */
    public static PathType pathType(long[] positions, PathType[] types, BlockGetter level, BlockPos pos) {
        long key = pos.asLong();
        Long2ObjectOpenHashMap<PathType> ov = OVERLAY.get();
        PathType t = ov.get(key);
        if (t != null) return t;
        int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & 4095;
        if (positions[idx] == key && (t = types[idx]) != null) return t;
        t = dev.lull.opt.ServerParallelItemsWalkInvoker.sleep$pathTypeFromState(level, pos);
        ov.put(key, t);
        return t;
    }

    /** stage 0: the overlays' entries written into the shared cache (in no particular order) */
    private static void mergePaths() {
        if (pathCache == null) return;
        var acc = (dev.lull.opt.ServerParallelItemsPathAccessor) pathCache;
        long[] positions = acc.sleep$positions();
        PathType[] types = acc.sleep$pathTypes();
        for (Long2ObjectOpenHashMap<PathType> ov : OVERLAYS) {
            for (Long2ObjectMap.Entry<PathType> en : ov.long2ObjectEntrySet()) {
                long key = en.getLongKey();
                int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & 4095;
                positions[idx] = key;
                types[idx] = en.getValue();
                pathOverlay++;
            }
            ov.clear();
        }
    }

    static void report() {
        long dw = walksParallel - lastWalksPar;
        if (dw > 0) System.out.println(String.format(java.util.Locale.ROOT, "[sleep/spec] last %d parallel walks: walk %.2f ms, of it the runs' parallel phase %.2f ms, their commit %.2f ms (%d runs)",
                dw, (nsParallel - lastPar) / 1e6 / dw, (nsPhase - lastPhase) / 1e6 / dw, (nsCommit - lastCommit) / 1e6 / dw, runs - lastRuns));
        lastPhase = nsPhase; lastCommit = nsCommit; lastPar = nsParallel; lastWalksPar = walksParallel; lastRuns = runs;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[sleep/spec] walks %d: runs %d, entities in runs %d (%.0f a walk); serial walk %.3f ms (%d), parallel walk %.3f ms (%d); deferred moves %d, removes %d, effects %d; path overlay entries %d, chunk misses %d; workers %d",
                walks, runs, inRuns, walks == 0 ? 0 : inRuns / (double) walks, walksSerial == 0 ? 0 : nsSerial / 1e6 / walksSerial, walksSerial,
                walksParallel == 0 ? 0 : nsParallel / 1e6 / walksParallel, walksParallel, deferredMoves, deferredRemoves, deferredFx, pathOverlay, chunkMisses,
                ServerReactor.pool().workerCount()));
    }
}
