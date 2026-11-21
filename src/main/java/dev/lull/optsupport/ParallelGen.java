package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * exp/parallel-worldgen: the worldgen actor schedules, the pool computes.
 *
 * <p>Vanilla already has one owner of generation state: the `worldgen` mailbox runs every ChunkGenerationTask, which
 * walks ChunkPyramid layer by layer (acquireStatusBump makes each (chunk, status) step run once). But it also runs
 * SURFACE, CARVERS and FEATURES inline, one chunk at a time for the whole level. Here those step bodies go to the
 * server's one background pool (Util.backgroundExecutor, the pool NOISE and BIOMES already use; a LabRuntime with
 * LabPoolsMixin), so the actor only schedules. No pool of our own: one set of workers sized to the machine.
 *
 * <p>Write radii from ChunkPyramid:
 * <ul>
 *   <li>SURFACE, CARVERS: 0, they write only their own chunk → any number at once;</li>
 *   <li>FEATURES: 1, it writes the 3x3 neighbourhood → a job claims its 9 chunks in a claims table. A job whose window
 *       overlaps a running one does not block a worker: it waits in a FIFO and is started by the job that frees its
 *       window. Earlier waiters reserve their windows during a scan, so a later job cannot overtake them forever.</li>
 * </ul>
 * The claims table is guarded by one monitor held for a few hash lookups (never while generating).
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
    private static final ArrayList<FeatureJob> WAITING = new ArrayList<>();
    /**
     * Local window hand-out (default; -Dlab.gen.featureFifo=true restores the FIFO scan above): a waiting job is kept by
     * its chunk; when a window is released only the jobs within 2 chunks of it can become startable, checked oldest
     * first; a new job starts at once if its window is free. No reservations: with GenGraph whole areas become ready for
     * FEATURES together, and the FIFO's reserved windows let only ~1.5 jobs run at once (a convoy). Starvation is
     * bounded - each chunk has FEATURES once, and a release re-checks its neighbours oldest first.
     */
    private static final boolean FIFO = Boolean.getBoolean("lab.gen.featureFifo");
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<FeatureJob> WAITING_AT = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long seq;

    private ParallelGen() {}

    private static Executor pool() {
        return Util.backgroundExecutor();
    }

    public static boolean shouldOffload(ChunkStep step) {
        if (BYPASS.get()) return false;
        ChunkStatus s = step.targetStatus();
        return s == ChunkStatus.SURFACE || s == ChunkStatus.CARVERS || s == ChunkStatus.FEATURES;
    }

    /** Runs the vanilla step body on the pool; `vanilla` re-enters ChunkMap.applyStep with the bypass flag set. */
    public static CompletableFuture<ChunkAccess> submit(ChunkPos pos, ChunkStep step, Supplier<CompletableFuture<ChunkAccess>> vanilla) {
        OFFLOADED.increment();
        if (GenTrace.ON) GenTrace.submitted.increment();
        long t0 = GenTrace.ON ? System.nanoTime() : 0;
        if (step.targetStatus() != ChunkStatus.FEATURES) return CompletableFuture.supplyAsync(() -> traced(t0, vanilla), pool());
        FEATURE_JOBS.increment();
        FeatureJob job = new FeatureJob(pos.x, pos.z, vanilla, t0);
        ArrayList<FeatureJob> start;
        synchronized (CLAIMS_LOCK) {
            job.seq = seq++;
            if (FIFO) {
                WAITING.add(job);
                start = startable();
            } else {
                start = new ArrayList<>(1);
                if (windowFree(job, CLAIMED)) {
                    claim(job);
                    start.add(job);
                } else {
                    WAITING_AT.put(ChunkPos.asLong(job.x, job.z), job);
                }
            }
        }
        if (!start.contains(job)) {
            WAITED.increment();
            job.waited = true;
        }
        startAll(start);
        return job.result;
    }

    /** a step body with the trace's timing (GenTrace: to a worker, running, done) */
    private static ChunkAccess traced(long t0, Supplier<CompletableFuture<ChunkAccess>> vanilla) {
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
            return WAITING.size() + WAITING_AT.size();
        }
    }

    private static ChunkAccess run(Supplier<CompletableFuture<ChunkAccess>> vanilla) {
        BYPASS.set(true);
        try {
            // these steps return completedFuture: join() only unwraps, it never blocks here
            return vanilla.get().join();
        } finally {
            BYPASS.set(false);
        }
    }

    private static final class FeatureJob implements Runnable {
        final int x, z;
        final Supplier<CompletableFuture<ChunkAccess>> body;
        final CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        final long t0;
        long seq;
        boolean waited;

        FeatureJob(int x, int z, Supplier<CompletableFuture<ChunkAccess>> body, long t0) {
            this.x = x;
            this.z = z;
            this.body = body;
            this.t0 = t0;
        }

        @Override
        public void run() {
            ChunkAccess c = null;
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
            ArrayList<FeatureJob> start;
            synchronized (CLAIMS_LOCK) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) CLAIMED.remove(ChunkPos.asLong(x + dx, z + dz));
                start = FIFO ? startable() : startableNear(x, z);
            }
            startAll(start);
            // complete after releasing: dependants scheduled by the completion may want this window
            if (err != null) result.completeExceptionally(err);
            else result.complete(c);
        }
    }

    /** Under CLAIMS_LOCK: claims and removes, in FIFO order, every waiter whose window is free and not reserved. */
    private static ArrayList<FeatureJob> startable() {
        ArrayList<FeatureJob> out = new ArrayList<>(2);
        LongOpenHashSet reserved = null;
        for (int i = 0; i < WAITING.size(); ) {
            FeatureJob j = WAITING.get(i);
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

    private static void claim(FeatureJob j) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) CLAIMED.add(ChunkPos.asLong(j.x + dx, j.z + dz));
    }

    /** Under CLAIMS_LOCK: the waiting jobs whose windows touch the window released at (x, z), oldest first. */
    private static ArrayList<FeatureJob> startableNear(int x, int z) {
        ArrayList<FeatureJob> cand = null;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                FeatureJob j = WAITING_AT.get(ChunkPos.asLong(x + dx, z + dz));
                if (j != null) (cand = cand == null ? new ArrayList<>(4) : cand).add(j);
            }
        ArrayList<FeatureJob> out = new ArrayList<>(2);
        if (cand == null) return out;
        if (cand.size() > 1) cand.sort((a, b) -> Long.compare(a.seq, b.seq));
        for (FeatureJob j : cand) {
            if (windowFree(j, CLAIMED)) {
                claim(j);
                WAITING_AT.remove(ChunkPos.asLong(j.x, j.z));
                out.add(j);
            }
        }
        return out;
    }

    private static boolean windowFree(FeatureJob j, LongOpenHashSet set) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) if (set.contains(ChunkPos.asLong(j.x + dx, j.z + dz))) return false;
        return true;
    }

    private static void startAll(ArrayList<FeatureJob> jobs) {
        Executor p = pool();
        for (int i = 0; i < jobs.size(); i++) p.execute(jobs.get(i));
    }

    public static String report() {
        int w;
        synchronized (CLAIMS_LOCK) {
            w = WAITING.size() + WAITING_AT.size();
        }
        return String.format(java.util.Locale.ROOT, "parallel worldgen: offloaded %d steps, %d feature jobs, %d waited for a window, %d still waiting",
                OFFLOADED.sumThenReset(), FEATURE_JOBS.sumThenReset(), WAITED.sumThenReset(), w);
    }
}
