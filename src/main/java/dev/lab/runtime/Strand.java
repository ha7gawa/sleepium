package dev.lab.runtime;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An abstract thread on a pool (1.3.0, the user's roles): a serial executor - its tasks run one at a time, in order,
 * never two at once - with no thread of its own. While it has tasks one pool worker drains it; when it is empty it
 * costs nothing. In place of a single-thread executor that holds a parked OS thread for the life of the game.
 */
public final class Strand extends AbstractExecutorService {
    private final Executor pool;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final String name;
    private volatile boolean shutdown;
    public volatile long ran;

    public Strand(String name, Executor pool) {
        this.name = name;
        this.pool = pool;
    }

    @Override
    public void execute(Runnable task) {
        if (shutdown) throw new java.util.concurrent.RejectedExecutionException(name + " is shut down");
        queue.add(task);
        if (scheduled.compareAndSet(false, true)) pool.execute(this::drain);
    }

    private void drain() {
        // a bounded batch, then the worker goes back to the pool (other work of the lane gets its turn)
        for (int i = 0; i < 64; i++) {
            Runnable r = queue.poll();
            if (r == null) break;
            try {
                r.run();
            } catch (Throwable t) {
                Thread th = Thread.currentThread();
                if (th.getUncaughtExceptionHandler() != null) th.getUncaughtExceptionHandler().uncaughtException(th, t);
            }
            ran++;
        }
        scheduled.set(false);
        // a task added between the last poll and the flag: schedule again (no lost task)
        if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) pool.execute(this::drain);
    }

    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> l = List.copyOf(queue); queue.clear(); return l; }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown && queue.isEmpty() && !scheduled.get(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (!isTerminated()) {
            if (System.nanoTime() > end) return false;
            Thread.sleep(1);
        }
        return true;
    }

    @Override
    public String toString() {
        return "strand " + name + " (" + ran + " tasks)";
    }
}
