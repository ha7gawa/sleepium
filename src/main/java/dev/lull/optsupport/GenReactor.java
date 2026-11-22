package dev.lull.optsupport;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.Util;

/**
 * WorldgenReactor: the `worldgen` mailbox removed. Vanilla runs every ChunkGenerationTask.runUntilWait inside one
 * ProcessorMailbox actor, re-posting a task each time a future it waits on completes (future → thenRun → mailbox →
 * actor → next layer): every step transition waits its turn on one logical thread.
 *
 * <p>What the actor serialised is safe without it: runUntilWait of one task never overlaps itself (it is re-posted only
 * after returning the future it waits on); what tasks share is atomic (acquireStatusBump's CAS runs each (chunk, status)
 * step once; holder futures, generation ref counts); SURFACE, CARVERS and FEATURES bodies are handed to ParallelGen
 * (FEATURES in claimed 3×3 windows); the other synchronous steps write only their own chunk (structure starts and
 * references, spawn, light initialisation) and post the rest to their own actors (light, main).
 *
 * <p>Here a posted task goes into a queue for its chunk's ticket level and one drain job goes to the server's one
 * background pool (a LabRuntime with LabPoolsMixin: a worker's own deque, stolen by idle ones); a drain job runs the
 * most urgent queued task (lowest level first, as ChunkTaskPriorityQueueSorter would). A continuation posts from the
 * thread that completed the future, so the next layer starts where the data is.
 */
public final class GenReactor {
    public static final boolean ENABLED = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenReactor");
    public static final LongAdder POSTED = new LongAdder(), RAN = new LongAdder(), GATED = new LongAdder();

    private static final int LEVELS = 64;
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<Runnable>[] QUEUES = new ConcurrentLinkedQueue[LEVELS];
    /** bit i set: queue i may be non-empty */
    private static final AtomicLong NONEMPTY = new AtomicLong();

    static {
        for (int i = 0; i < LEVELS; i++) QUEUES[i] = new ConcurrentLinkedQueue<>();
    }

    private static final Runnable DRAIN = GenReactor::drainOne;

    private GenReactor() {}

    private static Executor pool() {
        return Util.backgroundExecutor();
    }

    /** a task that knows its chunk (ChunkOrder: within a level the chunk nearest a player's way first) */
    private record AtChunk(long pos, Runnable task) implements Runnable {
        public void run() {
            task.run();
        }
    }

    /** as post(ticketLevel, task), for one chunk's work: with -Dlab.opt.GenNearFirst it is picked by where that chunk is */
    public static void post(int ticketLevel, long chunkPos, Runnable task) {
        post(ticketLevel, ChunkOrder.GEN ? new AtChunk(chunkPos, task) : task);
    }

    /** the queued task of a level nearest a player's way (the first {@code SCAN} looked at), or the oldest */
    private static final int SCAN = 512;

    private static Runnable pollNear(ConcurrentLinkedQueue<Runnable> q) {
        while (true) {
            Runnable best = null, first = null;
            double bestScore = Double.MAX_VALUE;
            int n = 0;
            for (Runnable r : q) {
                if (first == null) first = r;
                if (r instanceof AtChunk c) {
                    double s = ChunkOrder.score(c.pos());
                    if (s < bestScore) {
                        bestScore = s;
                        best = r;
                    }
                }
                if (++n >= SCAN) break;
            }
            Runnable pick = best != null ? best : first;
            if (pick == null) return null;
            if (q.remove(pick)) return pick;   // another drain job took it first: look again
        }
    }

    /** From any thread: run `task` on the pool, ordered by ticket level (lower = more urgent). */
    public static void post(int ticketLevel, Runnable task) {
        int lvl = Math.max(0, Math.min(LEVELS - 1, ticketLevel));
        QUEUES[lvl].add(task);
        long bit = 1L << lvl;
        long m;
        while (((m = NONEMPTY.get()) & bit) == 0 && !NONEMPTY.compareAndSet(m, m | bit)) {}
        POSTED.increment();
        pool().execute(DRAIN);
    }

    private static void drainOne() {
        while (true) {
            long m = NONEMPTY.get();
            if (m == 0) return;   // another drain job took it
            int lvl = Long.numberOfTrailingZeros(m);
            Runnable r = ChunkOrder.GEN ? pollNear(QUEUES[lvl]) : QUEUES[lvl].poll();
            if (r == null) {
                // empty now: clear the bit, then re-check (a post between poll and clear re-sets it)
                NONEMPTY.compareAndSet(m, m & ~(1L << lvl));
                if (!QUEUES[lvl].isEmpty()) {
                    long bit = 1L << lvl, x;
                    while (((x = NONEMPTY.get()) & bit) == 0 && !NONEMPTY.compareAndSet(x, x | bit)) {}
                }
                continue;
            }
            RAN.increment();
            r.run();
            return;
        }
    }

    /** diagnostics (-Dlab.reactor.debug=true): queue sizes by level, the non-empty bits, totals so far */
    public static String debug() {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < LEVELS; i++) if (!QUEUES[i].isEmpty()) q.append(' ').append(i).append(':').append(QUEUES[i].size());
        return "gen reactor: queues{" + q + " } bits " + Long.toBinaryString(NONEMPTY.get()) + ", posted " + POSTED.sum() + ", run " + RAN.sum() + ", gated " + GATED.sum();
    }

    static {
        if (Boolean.getBoolean("lab.reactor.debug")) {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    org.slf4j.LoggerFactory.getLogger("sleep").info("[sleep/reactor-debug] {} | {}", debug(), FullRing.debug());
                }
            }, "sleep-reactor-debug");
            t.setDaemon(true);
            t.start();
        }
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "worldgen reactor: %d task runs posted, %d run, %d waited for their data", POSTED.sumThenReset(), RAN.sumThenReset(), GATED.sumThenReset());
    }
}
