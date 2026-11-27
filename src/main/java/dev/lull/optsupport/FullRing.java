package dev.lull.optsupport;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * WorldgenReactor (completion ring with feedback): the FULL step's main-thread part (new LevelChunk, post-load, block
 * entities, tick containers, ChunkEvent.Load) arrives here instead of the main-thread mailbox, bucketed by ticket
 * level (lower first, as ChunkTaskPriorityQueueSorter orders). Vanilla runs every queued FULL task at once in the idle
 * phase; a generation wave finishing together gave ~200 chunks in one idle phase and their first ticks in one tick.
 *
 * <p>Feedback, as a congestion window (AIMD):
 * <ul>
 *   <li>time: in the idle phase a FULL task runs only if, at its measured average cost, it ends before the next tick's
 *       deadline with a margin — the idle time is used, never overrun;</li>
 *   <li>window: at most {@code cwnd} chunks enter per tick; after each tick, cwnd grows by 1 if the tick took less than
 *       the target ({@code lab.worldgen.tickTargetMs}, 25 ms) and shrinks ×0.7 if it took longer — the new chunks'
 *       first ticks (leaves, fluids) are what makes a tick long, so admission follows what the ticks can absorb;</li>
 *   <li>without ticks (spawn preparation) the window refills every 50 ms;</li>
 *   <li>when the main thread blocks on a chunk future (managedBlock) there is no limit: it must make progress.</li>
 * </ul>
 * Only the moment a chunk becomes FULL moves; the work is vanilla's.
 * <p>
 * {@code -Dlab.opt.FullRingAdaptive=true} (notes/93): the feedback is relative to the server's own tick, not to a fixed
 * 25 ms. With the fixed target a server whose ticks take 25 ms or more for any reason (a busy modded world) shrank the
 * window to 1 chunk a tick for good, and a server behind its schedule (no idle time before the deadline) ran one FULL
 * step per tick: a player flying 20 blocks/s on a 30 ms-tick server waited 4.5 s for each chunk entering the view (the
 * FULL step 8.4 s in this queue with the ticket throttle lifted). Adaptive:
 * <ul>
 *   <li>the target is max(25 ms, 1.25 x the slow average of the server's ticks + 5 ms) - the window shrinks when the new
 *       chunks make ticks longer than they were, not because they are long;</li>
 *   <li>a server that is behind (the deadline already passed) gets {@code lab.worldgen.behindBudgetMs} (10 ms) of FULL
 *       steps after each tick - vanilla, behind, runs the chunk tasks it has for up to 50 ms.</li>
 * </ul>
 */
public final class FullRing {
    public static final boolean ENABLED = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenReactor");
    private static final long TARGET_NANOS = Long.getLong("lab.worldgen.tickTargetMs", 25L) * 1_000_000L;
    public static final boolean ADAPTIVE = Boolean.getBoolean("lab.opt.FullRingAdaptive");
    private static final long BEHIND_BUDGET_NANOS = Long.getLong("lab.worldgen.behindBudgetMs", 10L) * 1_000_000L;
    /** slow average of the server's tick time (ADAPTIVE) */
    private static double baselineNanos = TARGET_NANOS * 0.6;
    /** when the current idle window's first FULL step ran (ADAPTIVE, behind the deadline) */
    private static long windowStartNanos;
    private static final int LEVELS = 64;
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<Runnable>[] QUEUES = new ConcurrentLinkedQueue[LEVELS];
    private static final AtomicInteger SIZE = new AtomicInteger();
    public static final LongAdder RAN = new LongAdder(), HELD_BY_WINDOW = new LongAdder(), HELD_BY_TIME = new LongAdder();

    /** main thread only */
    public static boolean idle;
    /** main thread: true during stopServer(), so FULL steps are never held back while shutting down (issue #16) */
    public static boolean shuttingDown;
    /**
     * main thread: how deep it is inside managedBlock (waiting for a chunk future). There the ring has no limit: the chunk
     * waited for may need a FULL step that is in the ring. The idle flag alone did not show it when the wait happened
     * inside a task the idle loop ran (a chunk's post-processing; a mod reading a farther chunk there, as Sable's
     * physics does): the window and the deadline held the step and the server waited for it for good (issue #15).
     */
    public static int blocking;
    /** main thread: the deadline of the next tick while idle */
    public static long deadlineNanos;
    private static double cwnd = 16;
    private static int allowance = 16;
    private static long lastRefill;
    /** EWMA of one FULL task's main-thread time */
    private static double costNanos = 60_000;
    private static double cwndSum;
    private static long cwndSamples;

    static {
        for (int i = 0; i < LEVELS; i++) QUEUES[i] = new ConcurrentLinkedQueue<>();
    }

    private FullRing() {}

    /** from any thread */
    public static void offer(int ticketLevel, Runnable fullTask) {
        QUEUES[Math.max(0, Math.min(LEVELS - 1, ticketLevel))].add(fullTask);
        SIZE.incrementAndGet();
        MainReactor.signal();
    }

    /** main thread, after each server tick: the congestion window follows the tick's cost */
    public static void onTick(long tickNanos) {
        long target = TARGET_NANOS;
        if (ADAPTIVE) {
            target = Math.max(TARGET_NANOS, (long) (baselineNanos * 1.25) + 5_000_000L);
            baselineNanos += (tickNanos - baselineNanos) * 0.05;
        }
        if (tickNanos < target) cwnd = Math.min(cwnd + 1, 1024);
        else cwnd = Math.max(1, cwnd * 0.7);
        allowance = (int) cwnd;
        lastRefill = System.nanoTime();
        cwndSum += cwnd;
        cwndSamples++;
    }

    private static void refillWithoutTicks() {
        long now = System.nanoTime();
        if (now - lastRefill >= 50_000_000L) {
            allowance = (int) cwnd;
            lastRefill = now;
        }
    }

    /** main thread, idle: may a FULL task run now? take: the caller runs it now (spends the window's first-task pass) */
    private static boolean mayRun(boolean take) {
        refillWithoutTicks();
        if (allowance <= 0) {
            HELD_BY_WINDOW.increment();
            return false;
        }
        // progress: the first task of every idle window runs whatever its measured cost. Otherwise a window shorter than
        // twice the average (prepareLevels gives 10 ms; the first FULL tasks of a fresh JVM cost several ms) would hold
        // every task forever, and the average, measured only on tasks that run, would never come down.
        if (deadlineNanos != firstOfWindow) {
            if (take) {
                firstOfWindow = deadlineNanos;
                windowStartNanos = net.minecraft.Util.getNanos();
            }
            return true;
        }
        // behind the schedule (ADAPTIVE): a budget after each tick instead of nothing
        if (ADAPTIVE && deadlineNanos <= windowStartNanos) {
            if (net.minecraft.Util.getNanos() + costNanos - windowStartNanos <= BEHIND_BUDGET_NANOS) return true;
            HELD_BY_TIME.increment();
            return false;
        }
        // deadlineNanos is the server's (Util.getNanos(): GLFW time on the client, System.nanoTime on a dedicated server)
        if (net.minecraft.Util.getNanos() + 2 * costNanos > deadlineNanos) {
            HELD_BY_TIME.increment();
            return false;
        }
        return true;
    }

    /** the deadline of the window whose first task was let through (main thread) */
    private static long firstOfWindow = Long.MIN_VALUE;

    /** main thread, idle: would runOne() run something now? (so the reactor does not sleep past work it may do) */
    public static boolean pending() {
        return SIZE.get() > 0 && (shuttingDown || mayRun(false));
    }

    /** main thread: run one FULL task if allowed; true if one ran */
    public static boolean runOne() {
        if (SIZE.get() == 0) return false;
        boolean limited = !shuttingDown && idle && blocking == 0;
        if (limited && !mayRun(true)) return false;
        for (int i = 0; i < LEVELS; i++) {
            Runnable r = QUEUES[i].poll();
            if (r != null) {
                SIZE.decrementAndGet();
                if (limited) allowance--;
                RAN.increment();
                MainReactor.found("FULL ring");
                long t = System.nanoTime();
                r.run();
                costNanos += ((System.nanoTime() - t) - costNanos) * 0.1;
                return true;
            }
        }
        return false;
    }

    /** diagnostics (racy reads, -Dlab.reactor.debug=true) */
    public static String debug() {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < LEVELS; i++) if (!QUEUES[i].isEmpty()) q.append(' ').append(i).append(':').append(QUEUES[i].size());
        return String.format(java.util.Locale.ROOT, "full ring: size %d queues{%s } idle %b, allowance %d, cwnd %.1f, deadline in %.1f ms, cost %.0f us, ran %d, held window %d / time %d",
                SIZE.get(), q, idle, allowance, cwnd, (deadlineNanos - net.minecraft.Util.getNanos()) / 1e6, costNanos / 1e3, RAN.sum(), HELD_BY_WINDOW.sum(), HELD_BY_TIME.sum());
    }

    public static String report() {
        String s = String.format(java.util.Locale.ROOT,
                "full ring: %d FULL steps run, window %.1f chunks/tick on average (now %.0f), FULL cost %.0f us, held by the window %d / by the deadline %d times, %d waiting",
                RAN.sumThenReset(), cwndSamples == 0 ? cwnd : cwndSum / cwndSamples, cwnd, costNanos / 1e3,
                HELD_BY_WINDOW.sumThenReset(), HELD_BY_TIME.sumThenReset(), SIZE.get());
        cwndSum = 0;
        cwndSamples = 0;
        return s;
    }
}
