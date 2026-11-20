package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;

/**
 * exp/parallel-worldgen: the worldgen actor schedules, the pool computes.
 *
 * <p>Vanilla already has one owner of generation state: the `worldgen` mailbox (1.20.1: behind
 * ChunkTaskPriorityQueueSorter) runs every step of ChunkMap.scheduleChunkGeneration once its chunk's range is ready
 * (ChunkHolder keeps one future per status, so each (chunk, status) step runs once). But it also runs SURFACE, CARVERS
 * and FEATURES inline - ChunkStatus.SimpleGenerationTask, synchronous - one chunk at a time for the whole level. Here
 * those step bodies go to the server's one background pool (Util.backgroundExecutor, the pool NOISE and BIOMES already
 * use; a LabRuntime with LabPoolsMixin), so the actor only schedules: a step returning an unfinished future lets the
 * mailbox go on with the next task, as NOISE's does. No pool of our own: one set of workers sized to the machine.
 *
 * <p>Write radii (1.20.1: the WorldGenRegion each step builds, its writeRadiusCutoff; the same as 1.21's ChunkPyramid):
 * <ul>
 *   <li>SURFACE, CARVERS: 0, they write only their own chunk → any number at once;</li>
 *   <li>FEATURES: 1, it writes the 3x3 neighbourhood → a job claims its 9 chunks in a claims table. A job whose window
 *       overlaps a running one does not block a worker: it waits in a FIFO and is started by the job that frees its
 *       window. Earlier waiters reserve their windows during a scan, so a later job cannot overtake them forever.</li>
 * </ul>
 * The claims table is guarded by one monitor held for a few hash lookups (never while generating). A neighbour's
 * FEATURES needs this chunk past CARVERS (1.20.1's getDependencyStatus: CARVERS at distance 1), so SURFACE and CARVERS
 * never overlap a neighbour's writes.
 *
 * <p>Failures: vanilla catches an exception of a synchronous step in scheduleChunkGeneration's compose (a crash report
 * "Exception generating new chunk", thrown again on the main thread); the step now runs on the pool, outside that try,
 * so {@link #crash} builds the same report there.
 */
public final class ParallelGen {
    public static final boolean ENABLED = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.ParallelWorldgenMixin");
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    public static final LongAdder OFFLOADED = new LongAdder();
    public static final LongAdder FEATURE_JOBS = new LongAdder();
    /** feature jobs that had to wait for a neighbour's window */
    public static final LongAdder WAITED = new LongAdder();

    private static final Object CLAIMS_LOCK = new Object();
    private static final LongOpenHashSet CLAIMED = new LongOpenHashSet();
    private static final ArrayList<FeatureJob<?>> WAITING = new ArrayList<>();

    private ParallelGen() {}

    /** -Dlab.gen.cap=N: at most N generation step bodies at once (0: no cap, the whole background pool) - the client in
     * singleplayer shares the machine with the integrated server's generation (11-20, 1.3.2 measurement) */
    static final int CAP = Integer.getInteger("lab.gen.cap", 0);
    /** -Dlab.gen.lowest: step bodies run at the lowest thread priority (the client's meshing and the server thread first) */
    static final boolean LOWEST = Boolean.getBoolean("lab.gen.lowest");
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> CAPPED = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger CAP_RUNNING = new java.util.concurrent.atomic.AtomicInteger();
    private static final Executor CAPPED_POOL = ParallelGen::capped;

    private static Executor pool() {
        return CAP > 0 ? CAPPED_POOL : Util.backgroundExecutor();
    }

    /** queues r; starts queued bodies on the background pool while fewer than CAP run */
    private static void capped(Runnable r) {
        CAPPED.add(r);
        drainCapped();
    }

    private static void drainCapped() {
        while (!CAPPED.isEmpty()) {
            int n = CAP_RUNNING.get();
            if (n >= CAP) return;
            if (!CAP_RUNNING.compareAndSet(n, n + 1)) continue;
            Runnable r = CAPPED.poll();
            if (r == null) {
                CAP_RUNNING.decrementAndGet();
                continue;   // another drainer took it; the loop re-checks the queue
            }
            Util.backgroundExecutor().execute(() -> {
                try {
                    r.run();
                } finally {
                    CAP_RUNNING.decrementAndGet();
                    drainCapped();
                }
            });
        }
    }

    public static boolean shouldOffload(ChunkStatus s) {
        if (BYPASS.get()) return false;
        return s == ChunkStatus.SURFACE || s == ChunkStatus.CARVERS || s == ChunkStatus.FEATURES;
    }

    /** whether this call of the step only hands it to the pool (ParallelWorldgenMixin on, not the re-entrant call) */
    public static boolean offloads(ChunkStatus s) {
        return ENABLED && shouldOffload(s);
    }

    /** Runs the vanilla step body on the pool; `vanilla` re-enters ChunkStatus.generate with the bypass flag set. */
    public static <T> CompletableFuture<T> submit(ChunkPos pos, ChunkStatus status, Supplier<CompletableFuture<T>> vanilla) {
        OFFLOADED.increment();
        if (GenTrace.ON) GenTrace.submitted.increment();
        long t0 = GenTrace.ON ? System.nanoTime() : 0;
        if (status != ChunkStatus.FEATURES) return CompletableFuture.supplyAsync(() -> traced(t0, vanilla), pool());
        FEATURE_JOBS.increment();
        FeatureJob<T> job = new FeatureJob<>(pos.x, pos.z, vanilla, t0);
        ArrayList<FeatureJob<?>> start;
        synchronized (CLAIMS_LOCK) {
            WAITING.add(job);
            start = startable();
        }
        if (!start.contains(job)) {
            WAITED.increment();
            job.waited = true;
        }
        startAll(start);
        return job.result;
    }

    /** a step body with the trace's timing (GenTrace: to a worker, running, done) */
    private static <T> T traced(long t0, Supplier<CompletableFuture<T>> vanilla) {
        if (!GenTrace.ON) return run(vanilla);
        long t1 = System.nanoTime();
        GenTrace.started.increment();
        GenTrace.queueNs.add(t1 - t0);
        GenTrace.maxOf(GenTrace.queueMax, t1 - t0);
        GenTrace.running.incrementAndGet();
        try {
            return run(vanilla);
        } finally {
            GenTrace.running.decrementAndGet();
            GenTrace.finished.increment();
            GenTrace.runNs.add(System.nanoTime() - t1);
        }
    }

    public static int waiting() {
        synchronized (CLAIMS_LOCK) {
            return WAITING.size();
        }
    }

    private static <T> T run(Supplier<CompletableFuture<T>> vanilla) {
        BYPASS.set(true);
        Thread t = Thread.currentThread();
        int prio = t.getPriority();
        if (LOWEST) t.setPriority(Thread.MIN_PRIORITY);
        try {
            // these steps return completedFuture: join() only unwraps, it never blocks here
            return vanilla.get().join();
        } finally {
            BYPASS.set(false);
            if (LOWEST) t.setPriority(prio);
        }
    }

    /**
     * A synchronous step's body threw: vanilla's scheduleChunkGeneration report - the crash report thrown on the main
     * thread (its executor logs it) and here (the step's future completes with it).
     */
    public static ReportedException crash(Throwable exception, ServerLevel level, ChunkGenerator generator, ChunkPos pos) {
        if (exception instanceof ReportedException r) return r;
        CrashReport crashreport = CrashReport.forThrowable(exception, "Exception generating new chunk");
        CrashReportCategory crashreportcategory = crashreport.addCategory("Chunk to be generated");
        crashreportcategory.setDetail("Location", String.format(java.util.Locale.ROOT, "%d,%d", pos.x, pos.z));
        crashreportcategory.setDetail("Position hash", ChunkPos.asLong(pos.x, pos.z));
        crashreportcategory.setDetail("Generator", generator);
        level.getServer().execute(() -> {
            throw new ReportedException(crashreport);
        });
        return new ReportedException(crashreport);
    }

    private static final class FeatureJob<T> implements Runnable {
        final int x, z;
        final Supplier<CompletableFuture<T>> body;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final long t0;
        boolean waited;

        FeatureJob(int x, int z, Supplier<CompletableFuture<T>> body, long t0) {
            this.x = x;
            this.z = z;
            this.body = body;
            this.t0 = t0;
        }

        @Override
        public void run() {
            T c = null;
            Throwable err = null;
            if (GenTrace.ON && waited) {
                long w = System.nanoTime() - t0;
                GenTrace.featureWaits.increment();
                GenTrace.featureWaitNs.add(w);
                GenTrace.maxOf(GenTrace.featureWaitMax, w);
            }
            try {
                c = ParallelGen.traced(t0, body);
            } catch (Throwable t) {
                err = t;
            }
            ArrayList<FeatureJob<?>> start;
            synchronized (CLAIMS_LOCK) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) CLAIMED.remove(ChunkPos.asLong(x + dx, z + dz));
                start = startable();
            }
            startAll(start);
            // complete after releasing: dependants scheduled by the completion may want this window
            if (err != null) result.completeExceptionally(err);
            else result.complete(c);
        }
    }

    /** Under CLAIMS_LOCK: claims and removes, in FIFO order, every waiter whose window is free and not reserved. */
    private static ArrayList<FeatureJob<?>> startable() {
        ArrayList<FeatureJob<?>> out = new ArrayList<>(2);
        LongOpenHashSet reserved = null;
        for (int i = 0; i < WAITING.size(); ) {
            FeatureJob<?> j = WAITING.get(i);
            if (windowFree(j, CLAIMED) && (reserved == null || windowFree(j, reserved))) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) CLAIMED.add(ChunkPos.asLong(j.x + dx, j.z + dz));
                WAITING.remove(i);
                out.add(j);
            } else {
                if (reserved == null) reserved = new LongOpenHashSet();
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) reserved.add(ChunkPos.asLong(j.x + dx, j.z + dz));
                i++;
            }
        }
        return out;
    }

    private static boolean windowFree(FeatureJob<?> j, LongOpenHashSet set) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) if (set.contains(ChunkPos.asLong(j.x + dx, j.z + dz))) return false;
        return true;
    }

    private static void startAll(ArrayList<FeatureJob<?>> jobs) {
        Executor p = pool();
        for (int i = 0; i < jobs.size(); i++) p.execute(jobs.get(i));
    }

    public static String report() {
        int w;
        synchronized (CLAIMS_LOCK) {
            w = WAITING.size();
        }
        return String.format(java.util.Locale.ROOT, "parallel worldgen: offloaded %d steps, %d feature jobs, %d waited for a window, %d still waiting",
                OFFLOADED.sumThenReset(), FEATURE_JOBS.sumThenReset(), WAITED.sumThenReset(), w);
    }
}
