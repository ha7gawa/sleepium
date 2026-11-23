package dev.lull.optsupport;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Decoupled frames with other mods (notes/100): the cooperative mode. Mods expect a frame and the client tick never to
 * run at once (vanilla runs both on one thread) - their render handlers read lists their tick handlers change
 * (furnace_open's ConcurrentModificationException, 11-22). In this mode the main thread holds a lock while it runs its
 * queued tasks and the client tick, and lets it go at safe points - between entity ticks, between tasks, while it waits
 * for the frame thread; a frame (or an extra frame) takes the lock around its drawing. A frame never runs alongside the
 * tick's code, yet it does not wait for a long tick's end: it is drawn at the tick's next safe point (frames stay
 * smooth; the tick and the frame no longer overlap in time). Without other mods (vanilla, Sleepium) the mode is off:
 * the frame and the tick overlap as before.
 *
 * -Dlab.frameThread.coop=auto|true|false (auto: on when a mod other than minecraft / forge / sleepium is loaded).
 */
public final class FrameCoop {
    private FrameCoop() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    private static final String MODE = System.getProperty("lab.frameThread.coop", "auto");
    private static final java.util.Set<String> OWN = java.util.Set.of("minecraft", "forge", "sleepium", "mixinextras");

    /** decided once, at the frame thread's start */
    static volatile boolean on;
    private static boolean decided;
    /** fair: the main thread's re-lock at a safe point queues behind the waiting frame */
    private static final ReentrantLock L = new ReentrantLock(true);
    /** the main thread holds L (its busy stretch) */
    private static boolean mainIn;
    public static long yields, frameTakes, frameWaits, frameWaitNs, maxFrameWaitNs;
    private static final boolean TRACE = Boolean.getBoolean("lab.frameThread.coopTrace");
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> LONG = new java.util.concurrent.ConcurrentHashMap<>();
    /** the main thread (for the trace's samples) */
    static volatile Thread main;

    static void decide() {
        if (decided) return;
        decided = true;
        int mods;
        StringBuilder names = new StringBuilder();
        try {
            var list = net.minecraftforge.fml.ModList.get().getMods();
            mods = 0;
            for (var m : list) {
                if (OWN.contains(m.getModId())) continue;
                if (mods++ < 6) names.append(names.length() == 0 ? "" : ", ").append(m.getModId());
            }
        } catch (Throwable t) {
            mods = 99;
        }
        on = "true".equals(MODE) || "auto".equals(MODE) && mods > 0;
        LOG.info("[sleep/opt] frame thread: cooperative mode {} ({} other mods{})", on ? "on - frames are drawn at the tick's safe points" : "off - frames overlap the tick",
                mods, mods > 0 ? ": " + names + (mods > 6 ? ", ..." : "") : "");
    }

    public static boolean on() {
        return on;
    }

    // ------------------------------------------------------------------ the main thread

    /** the main thread starts its queued tasks or its client tick */
    public static void mainEnter() {
        if (!on || mainIn) return;
        main = Thread.currentThread();
        L.lock();
        mainIn = true;
    }

    /** ... and ends them */
    public static void mainLeave() {
        if (!mainIn) return;
        mainIn = false;
        L.unlock();
    }

    /** a safe point of the main thread's busy stretch: a frame waiting is drawn now */
    public static void safepoint() {
        if (mainIn && L.hasQueuedThreads() && L.isHeldByCurrentThread()) {
            L.unlock();
            L.lock();   // (fair: after the frame)
            yields++;
        }
    }

    /** the main thread is about to wait for the frame thread: the lock let go meanwhile; returns whether it was held */
    public static boolean releaseForWait() {
        if (!mainIn || !L.isHeldByCurrentThread()) return false;
        mainIn = false;
        L.unlock();
        return true;
    }

    public static void reacquireAfterWait(boolean was) {
        if (!was) return;
        L.lock();
        mainIn = true;
    }

    // ------------------------------------------------------------------ the frame thread

    /** the frame thread before it draws (or runs mods' queued render work): true when it took the lock */
    public static boolean frameEnter() {
        if (!on) return false;
        frameTakes++;
        // (lock(), never tryLock(): tryLock takes a fair lock past the threads queued for it - the frame thread's extra
        // frames, one after another, starved the main thread's re-lock at its safe point: a hung client, 11-23)
        boolean busy = L.isLocked() || L.hasQueuedThreads();
        long t0 = busy ? System.nanoTime() : 0;
        if (busy && TRACE && main != null) {
            // (-Dlab.frameThread.coopTrace: a wait past 5 ms samples where the main thread is - a stretch with no safe point)
            try {
                if (!L.tryLock(5, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    StackTraceElement[] st = main.getStackTrace();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0, n = 0; i < st.length && n < 6; i++) {
                        String c = st[i].getClassName();
                        if (c.startsWith("java.") || c.startsWith("jdk.")) continue;
                        sb.append(n++ > 0 ? " <- " : "").append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName());
                    }
                    LONG.merge(sb.toString(), 1, Integer::sum);
                    L.lock();
                }
            } catch (InterruptedException e) {
                L.lock();
            }
        } else L.lock();
        if (busy) {
            long w = System.nanoTime() - t0;
            frameWaits++;
            frameWaitNs += w;
            if (w > maxFrameWaitNs) maxFrameWaitNs = w;
        }
        return true;
    }

    public static void frameLeave(boolean took) {
        if (took) L.unlock();
    }

    public static String stats() {
        return on ? String.format(java.util.Locale.ROOT, "cooperative: %d frame takes, %d waited (%.2f ms mean, %.2f max), %d main yields", frameTakes, frameWaits,
                frameWaits == 0 ? 0 : frameWaitNs / 1e6 / frameWaits, maxFrameWaitNs / 1e6, yields) + (TRACE ? "; waits past 5 ms where the main thread was: " + top() : "") : "cooperative off";
    }

    private static String top() {
        return LONG.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8).map(e -> e.getValue() + "x " + e.getKey()).toList().toString();
    }
}
