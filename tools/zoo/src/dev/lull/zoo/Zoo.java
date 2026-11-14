package dev.lull.zoo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mods' pool (lab prototype, 1.3.0 "no zoo"): the executors mods make with Executors.newFixedThreadPool /
 * newSingleThreadExecutor / newCachedThreadPool become lanes on one shared elastic pool. A lane keeps its executor's
 * contract - at most n of its tasks at once (1: in order, one at a time) - but holds no thread while it has no work.
 * The pool grows while all its threads are busy (a blocking task never starves another lane: no deadlock that the
 * original executors would not have) and its idle threads end after a while. Threads alive = the work in flight.
 */
public final class Zoo {
    private Zoo() {}

    private static final AtomicInteger NUM = new AtomicInteger();
    static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            Long.getLong("sleep.zoo.keepAliveMs", 10_000), TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "Zoo-Worker-" + NUM.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    static final Map<String, AtomicInteger> MADE = new ConcurrentHashMap<>();
    static final AtomicLong TASKS = new AtomicLong();
    static final AtomicInteger LANES = new AtomicInteger();

    // ---- the rewritten calls (descriptors of Executors' plus the calling class) ----

    public static ExecutorService newFixedThreadPool(int n, String caller) {
        return lane(n, null, caller, "fixed");
    }

    public static ExecutorService newFixedThreadPool(int n, ThreadFactory f, String caller) {
        return lane(n, f, caller, "fixed");
    }

    public static ExecutorService newSingleThreadExecutor(String caller) {
        return lane(1, null, caller, "single");
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory f, String caller) {
        return lane(1, f, caller, "single");
    }

    public static ExecutorService newCachedThreadPool(String caller) {
        return lane(Integer.MAX_VALUE, null, caller, "cached");
    }

    public static ExecutorService newCachedThreadPool(ThreadFactory f, String caller) {
        return lane(Integer.MAX_VALUE, f, caller, "cached");
    }

    private static ExecutorService lane(int n, ThreadFactory f, String caller, String kind) {
        if (n <= 0) throw new IllegalArgumentException();
        MADE.computeIfAbsent(caller.replace('/', '.') + " " + kind, k -> new AtomicInteger()).incrementAndGet();
        LANES.incrementAndGet();
        String name = caller.substring(caller.lastIndexOf('/') + 1);
        int prio = Thread.NORM_PRIORITY;
        ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        boolean probe = !Boolean.getBoolean("sleep.zoo.noProbe");
        if (f != null && probe) {
            // what the mod's factory would have made: its name, priority and class loader (never started)
            try {
                Thread t = f.newThread(() -> {});
                if (t != null) {
                    name = t.getName();
                    prio = t.getPriority();
                    ccl = t.getContextClassLoader();
                }
            } catch (Throwable ignored) {
            }
        }
        return new Lane(name, n, prio, ccl);
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("zoo: " + LANES.get() + " lanes, " + TASKS.get() + " tasks, pool threads now " + POOL.getPoolSize()
                + " (largest " + POOL.getLargestPoolSize() + ", active " + POOL.getActiveCount() + ")");
        List<Map.Entry<String, AtomicInteger>> l = new ArrayList<>(MADE.entrySet());
        l.sort((a, b) -> b.getValue().get() - a.getValue().get());
        for (var e : l) sb.append("\n    ").append(e.getValue().get()).append(" x ").append(e.getKey());
        return sb.toString();
    }

    /** an executor of the mods' pool: at most `permits` of its tasks at once, in the order given */
    static final class Lane extends AbstractExecutorService {
        final String name;
        final int permits, prio;
        final ClassLoader ccl;
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger running = new AtomicInteger();
        volatile boolean shutdown;
        final Object done = new Object();

        Lane(String name, int permits, int prio, ClassLoader ccl) {
            this.name = name;
            this.permits = permits;
            this.prio = prio;
            this.ccl = ccl;
        }

        @Override
        public void execute(Runnable task) {
            if (task == null) throw new NullPointerException();
            if (shutdown) throw new RejectedExecutionException(name + " is shut down");
            queue.add(task);
            schedule();
        }

        private void schedule() {
            while (!queue.isEmpty()) {
                int r = running.get();
                if (r >= permits) return;
                if (running.compareAndSet(r, r + 1)) {
                    try {
                        POOL.execute(this::drain);
                    } catch (RejectedExecutionException e) {
                        running.decrementAndGet();
                        throw e;
                    }
                    return;   // one drainer more per call: it takes the rest, others start as tasks arrive
                }
            }
        }

        private void drain() {
            Thread th = Thread.currentThread();
            String own = th.getName();
            ClassLoader ownCcl = th.getContextClassLoader();
            th.setName(own + " " + name);
            th.setContextClassLoader(ccl);
            if (prio != Thread.NORM_PRIORITY) th.setPriority(prio);
            try {
                for (int i = 0; i < 256; i++) {
                    Runnable r = queue.poll();
                    if (r == null) break;
                    try {
                        r.run();
                    } catch (Throwable t) {
                        var h = th.getUncaughtExceptionHandler();
                        if (h != null) h.uncaughtException(th, t);
                    }
                    TASKS.incrementAndGet();
                }
            } finally {
                if (prio != Thread.NORM_PRIORITY) th.setPriority(Thread.NORM_PRIORITY);
                th.setContextClassLoader(ownCcl);
                th.setName(own);
                running.decrementAndGet();
                // a task added between the last poll and the decrement: another drainer (no lost task)
                if (!queue.isEmpty()) schedule();
                else if (shutdown) synchronized (done) { done.notifyAll(); }
            }
        }

        @Override public void shutdown() { shutdown = true; synchronized (done) { done.notifyAll(); } }
        @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> l = new ArrayList<>(queue); queue.clear(); return l; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty() && running.get() == 0; }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            synchronized (done) {
                while (!isTerminated()) {
                    long left = end - System.nanoTime();
                    if (left <= 0) return false;
                    done.wait(Math.max(1, Math.min(left / 1_000_000, 50)));
                }
            }
            return true;
        }

        @Override public String toString() { return "zoo lane " + name + " (" + permits + ")"; }
    }
}
