import dev.lull.zoo.Zoo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lanes of the mods' pool under contention: a single lane runs its tasks in order and never two at once, a fixed(n) lane
 * never more than n at once, no task is lost, a blocking task in one lane does not stop another, awaitTermination works.
 * Also: what one task hand-off costs vs a plain single-thread executor.
 */
public class ZooTest {
    public static void main(String[] a) throws Exception {
        // 1. order and exclusivity, 8 producers into one single lane
        ExecutorService single = Zoo.newSingleThreadExecutor("test/Single");
        int producers = 8, per = 200_000;
        AtomicLong[] last = new AtomicLong[producers];
        for (int i = 0; i < producers; i++) last[i] = new AtomicLong(-1);
        AtomicInteger inside = new AtomicInteger(), maxInside = new AtomicInteger(), ran = new AtomicInteger(), outOfOrder = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ps = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            int pid = p;
            ps[p] = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int k = 0; k < per; k++) {
                    long seq = k;
                    single.execute(() -> {
                        int in = inside.incrementAndGet();
                        maxInside.accumulateAndGet(in, Math::max);
                        if (last[pid].get() != seq - 1) outOfOrder.incrementAndGet();
                        last[pid].set(seq);
                        ran.incrementAndGet();
                        inside.decrementAndGet();
                    });
                }
            });
            ps[p].start();
        }
        long t0 = System.nanoTime();
        go.countDown();
        for (Thread t : ps) t.join();
        single.shutdown();
        boolean done = single.awaitTermination(30, TimeUnit.SECONDS);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("single: ran %d of %d, max at once %d, out of order %d, terminated %s, %.0f ms (%.0f ns a task)%n",
                ran.get(), producers * per, maxInside.get(), outOfOrder.get(), done, ms, ms * 1e6 / (producers * per));

        // 2. fixed(3): never more than 3 at once
        ExecutorService fixed = Zoo.newFixedThreadPool(3, "test/Fixed");
        AtomicInteger in3 = new AtomicInteger(), max3 = new AtomicInteger(), ran3 = new AtomicInteger();
        for (int k = 0; k < 2000; k++) fixed.execute(() -> {
            int in = in3.incrementAndGet();
            max3.accumulateAndGet(in, Math::max);
            long end = System.nanoTime() + 20_000;
            while (System.nanoTime() < end) Thread.onSpinWait();
            ran3.incrementAndGet();
            in3.decrementAndGet();
        });
        fixed.shutdown();
        boolean fixedDone = fixed.awaitTermination(30, TimeUnit.SECONDS);
        System.out.printf("fixed(3): ran %d of 2000, max at once %d, terminated %s%n", ran3.get(), max3.get(), fixedDone);

        // 3. a lane blocked forever does not stop another lane
        ExecutorService stuck = Zoo.newSingleThreadExecutor("test/Stuck");
        CountDownLatch never = new CountDownLatch(1);
        stuck.execute(() -> { try { never.await(); } catch (InterruptedException ignored) {} });
        ExecutorService other = Zoo.newSingleThreadExecutor("test/Other");
        CountDownLatch otherRan = new CountDownLatch(1);
        other.execute(otherRan::countDown);
        System.out.printf("blocked lane, another lane still runs: %s%n", otherRan.await(5, TimeUnit.SECONDS));
        never.countDown();

        // 4. hand-off cost: one producer, ping-pong vs a plain single-thread executor
        for (int round = 0; round < 3; round++) {
            System.out.printf("ping-pong 20000: zoo lane %.1f us, JDK single %.1f us%n",
                    pingPong(Zoo.newSingleThreadExecutor("test/Ping")), pingPong(java.util.concurrent.Executors.newSingleThreadExecutor()));
        }
        System.out.println(Zoo.report());
        System.exit(0);
    }

    static double pingPong(ExecutorService e) throws Exception {
        int n = 20_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) e.submit(() -> {}).get();
        double us = (System.nanoTime() - t0) / 1e3 / n;
        e.shutdown();
        return us;
    }
}
