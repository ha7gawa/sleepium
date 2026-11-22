package dev.lull;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measurement only (-Dlab.light.probe=true, mixins LightProbe*, notes/84): what the server's light engine costs and how
 * serial it is. The light actor of a level (ThreadedLevelLightEngine) runs its batches one at a time: runUpdate = the
 * batch's PRE tasks (section status, light enabled, sources: propagateLightSources), the BFS (LevelLightEngine.
 * runLightUpdates: block then sky; per engine checkNode, decrease pass, increase pass, storage publish) and the POST tasks
 * (futures of initializeLight / lightChunk / waitForPendingTasks completed).
 *
 * <p>Busy time of the actor against wall time, the queue at each batch start, and the latency of the futures
 * (call -> done): lightChunk (the LIGHT step of a chunk) and waitForPendingTasks (what a chunk's sending waits for).
 */
public final class LightProbe {
    private LightProbe() {}

    public static final boolean ON = Boolean.getBoolean("lab.light.probe");

    /** ThreadedLevelLightEngine's queue for the timeline (LightProbeThreadedMixin) */
    public interface Engine {
        int sleep$pendingTasks();

        /** the light mailbox's queued runnables (1.20.1's LightCheck drain) */
        int sleep$mailboxSize();
    }

    // batch phases (ns), counts
    static final LongAdder BATCHES = new LongAdder(), TASKS = new LongAdder(), PRE = new LongAdder(), BFS = new LongAdder(), POST = new LongAdder();
    /** cumulative busy ns of all light actors (the timeline differences it per sample) */
    public static final AtomicLong BUSY = new AtomicLong();
    static final AtomicLong MAX_QUEUE = new AtomicLong(), QUEUE_SUM = new AtomicLong();
    // per engine: 0 block, 1 sky: runLightUpdates ns, checkNode count, decrease ns, decrease entries, increase ns, increase entries,
    // publish (markNewInconsistencies + swapSectionMap) ns, sources (propagateLightSources) ns, sources calls, setLightEnabled ns
    static final int RUN = 0, CHECKS = 1, DEC_NS = 2, DEC_N = 3, INC_NS = 4, INC_N = 5, PUB_NS = 6, SRC_NS = 7, SRC_N = 8, ENABLE_NS = 9, K = 10;
    static final AtomicLongArray ENG = new AtomicLongArray(2 * K);
    // calls into the actor
    static final LongAdder INIT_CALLS = new LongAdder(), LIGHT_CALLS = new LongAdder(), CHECK_BLOCK = new LongAdder(), WAIT_CALLS = new LongAdder(),
            SECTION_STATUS = new LongAdder();
    // latencies (ms buckets: 0,1,2,4,...,2^14) for lightChunk and waitForPendingTasks; initializeLight too
    static final int B = 17;
    static final AtomicLongArray LAT = new AtomicLongArray(3 * B);
    static final AtomicLongArray LAT_SUM = new AtomicLongArray(3);
    static final AtomicLongArray LAT_MAX = new AtomicLongArray(3);
    public static final int L_LIGHT = 0, L_WAIT = 1, L_INIT = 2;
    private static volatile long windowStart = System.nanoTime();

    public static void batch(long pre, long bfs, long post, int tasks, int queue) {
        BATCHES.increment();
        TASKS.add(tasks);
        PRE.add(pre);
        BFS.add(bfs);
        POST.add(post);
        BUSY.addAndGet(pre + bfs + post);
        QUEUE_SUM.addAndGet(queue);
        MAX_QUEUE.accumulateAndGet(queue, Math::max);
    }

    public static void eng(boolean sky, int what, long v) {
        ENG.addAndGet((sky ? K : 0) + what, v);
    }

    public static void latency(int kind, long nanos) {
        long ms = nanos / 1_000_000L;
        int b = ms <= 0 ? 0 : Math.min(B - 1, 64 - Long.numberOfLeadingZeros(ms));
        LAT.incrementAndGet(kind * B + b);
        LAT_SUM.addAndGet(kind, nanos);
        LAT_MAX.accumulateAndGet(kind, nanos, Math::max);
    }

    /** block-light sections created, and of them next to a section holding block light (LightProbeSectionMixin) */
    static final LongAdder BORN = new LongAdder(), BORN_LIT = new LongAdder();

    /** checkNode of a light source that vanilla will not light (section not stored / light off in the column) */
    static final LongAdder UNLIT = new LongAdder(), UNLIT_LATE = new LongAdder();

    public static void unlitSource(net.minecraft.core.BlockPos p, net.minecraft.world.level.block.state.BlockState s, boolean storing, boolean on, Object chunk) {
        UNLIT.increment();
        // (a chunk before LIGHT is normal: its lightChunk scans every source later; one at LIGHT or after is never re-scanned)
        boolean late = !(chunk instanceof net.minecraft.world.level.chunk.ChunkAccess a0) || a0.getStatus().isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.LIGHT);
        if (!late) return;
        UNLIT_LATE.increment();
        if (UNLIT_LATE.sum() <= 30) System.out.println("[sleep/light] source not lit by checkNode: " + p.toShortString() + " " + s + " section stored " + storing
                + ", light on in column " + on + ", chunk " + chunk.getClass().getSimpleName()
                + (chunk instanceof net.minecraft.world.level.chunk.ChunkAccess a ? " status " + a.getStatus() : "") + " on " + Thread.currentThread().getName());
    }

    public static void sectionBorn() {
        BORN.increment();
    }

    public static void sectionBornNextToLight() {
        BORN_LIT.increment();
    }

    public static void call(int kind) {
        switch (kind) {
            case 0 -> INIT_CALLS.increment();
            case 1 -> LIGHT_CALLS.increment();
            case 2 -> CHECK_BLOCK.increment();
            case 3 -> WAIT_CALLS.increment();
            default -> SECTION_STATUS.increment();
        }
    }

    public static void reset() {
        BATCHES.reset(); TASKS.reset(); PRE.reset(); BFS.reset(); POST.reset();
        MAX_QUEUE.set(0); QUEUE_SUM.set(0);
        for (int i = 0; i < ENG.length(); i++) ENG.set(i, 0);
        BORN.reset(); BORN_LIT.reset(); UNLIT.reset(); UNLIT_LATE.reset();
        INIT_CALLS.reset(); LIGHT_CALLS.reset(); CHECK_BLOCK.reset(); WAIT_CALLS.reset(); SECTION_STATUS.reset();
        for (int i = 0; i < LAT.length(); i++) LAT.set(i, 0);
        for (int i = 0; i < 3; i++) { LAT_SUM.set(i, 0); LAT_MAX.set(i, 0); }
        windowStart = System.nanoTime();
    }

    private static String lat(int kind, String name) {
        long n = 0;
        for (int b = 0; b < B; b++) n += LAT.get(kind * B + b);
        if (n == 0) return name + " n/a";
        long[] c = new long[B];
        for (int b = 0; b < B; b++) c[b] = LAT.get(kind * B + b);
        return String.format(Locale.ROOT, "%s n %d mean %.1f ms p50 <%s p90 <%s p99 <%s max %.0f ms", name, n, LAT_SUM.get(kind) / 1e6 / n,
                pct(c, n, 0.5), pct(c, n, 0.9), pct(c, n, 0.99), LAT_MAX.get(kind) / 1e6);
    }

    private static String pct(long[] c, long n, double q) {
        long need = (long) Math.ceil(n * q), acc = 0;
        for (int b = 0; b < B; b++) {
            acc += c[b];
            if (acc >= need) return b == 0 ? "1 ms" : (1L << b) + " ms";
        }
        return "inf";
    }

    /** one line per part; chunks = chunks the window made FULL (per-chunk costs) */
    public static String report(int chunks) {
        double wall = (System.nanoTime() - windowStart) / 1e9;
        long batches = BATCHES.sum();
        double busy = (PRE.sum() + BFS.sum() + POST.sum()) / 1e9;
        double per = Math.max(1, chunks);
        StringBuilder b = new StringBuilder("light probe:\n");
        b.append(String.format(Locale.ROOT, "  actor busy %.2f s of %.2f s wall (%.0f %%); %d batches, %d tasks (%.0f a batch), queue at batch start mean %.0f max %d;"
                        + " per FULL chunk %.3f ms (pre %.3f, bfs %.3f, post %.3f)%n",
                busy, wall, 100 * busy / Math.max(1e-9, wall), batches, TASKS.sum(), batches == 0 ? 0 : (double) TASKS.sum() / batches,
                batches == 0 ? 0 : (double) QUEUE_SUM.get() / batches, MAX_QUEUE.get(), busy * 1e3 / per, PRE.sum() / 1e6 / per, BFS.sum() / 1e6 / per, POST.sum() / 1e6 / per));
        for (int e = 0; e < 2; e++) {
            int o = e * K;
            b.append(String.format(Locale.ROOT, "  %s: runLightUpdates %.3f ms a chunk (checkNode %d = %.1f a chunk, decrease %.3f ms / %d entries, increase %.3f ms / %d entries,"
                            + " publish %.3f ms); sources %.3f ms a chunk (%d calls); setLightEnabled %.3f ms a chunk%n",
                    e == 0 ? "block" : "sky", ENG.get(o + RUN) / 1e6 / per, ENG.get(o + CHECKS), ENG.get(o + CHECKS) / per, ENG.get(o + DEC_NS) / 1e6 / per, ENG.get(o + DEC_N),
                    ENG.get(o + INC_NS) / 1e6 / per, ENG.get(o + INC_N), ENG.get(o + PUB_NS) / 1e6 / per, ENG.get(o + SRC_NS) / 1e6 / per, ENG.get(o + SRC_N),
                    ENG.get(o + ENABLE_NS) / 1e6 / per));
        }
        b.append(String.format(Locale.ROOT, "  calls: initializeLight %d, lightChunk %d, checkBlock %d, updateSectionStatus %d, waitForPendingTasks %d%n",
                INIT_CALLS.sum(), LIGHT_CALLS.sum(), CHECK_BLOCK.sum(), SECTION_STATUS.sum(), WAIT_CALLS.sum()));
        b.append(String.format(Locale.ROOT, "  block-light sections created %d, next to a lit section %d (start dark: light from before stays out); sources checked but not lit (section not stored / light off) %d, of them in chunks at LIGHT or later %d%n", BORN.sum(), BORN_LIT.sum(), UNLIT.sum(), UNLIT_LATE.sum()));
        b.append("  latency (call -> future done): ").append(lat(L_INIT, "initializeLight")).append("; ").append(lat(L_LIGHT, "lightChunk"))
                .append("; ").append(lat(L_WAIT, "waitForPendingTasks"));
        return b.toString();
    }
}
