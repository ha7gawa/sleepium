import dev.lab.runtime.LabRuntime;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Standalone fan-out benchmark (1.3.0 pool core): one index range spread over T threads per call, the way a frame
 * spreads its entities - LabRuntime.forRange (fixed chunks) against a guided self-scheduling pool in the style of the
 * user's bench_cpu.c (one shared claim counter, chunk = remaining / (T * 4) but at least a grain, the caller works as the
 * last worker, per-worker flags on their own cache lines; waiting = a short spin, then park - never Sleep(0)) and a
 * ForkJoinPool parallel stream. Workloads: n items of ~w ns each, even or skewed (every 10th item 20x).
 *
 * <p>Run: java -cp build/classes/java/main tools/bench/PoolBench.java [threads]
 */
public final class PoolBench {
    // ------------------------------------------------------------------ guided pool (bench_cpu.c style)
    static final class GuidedPool {
        static final class Slot {   // a worker's flags, alone on their cache line (padding both sides)
            long p0, p1, p2, p3, p4, p5, p6;
            volatile int start;      // generation to run
            volatile boolean parked;
            Thread thread;
            long q0, q1, q2, q3, q4, q5, q6;
        }

        final int t;   // threads in a fan-out, the caller included
        final Slot[] slots;
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        volatile int gen;
        volatile IntConsumer body;
        volatile int n, grain, div = 4;
        volatile Thread waiter;
        final long spinNs;

        GuidedPool(int t, long spinNs) {
            this.t = t;
            this.spinNs = spinNs;
            slots = new Slot[t - 1];
            for (int i = 0; i < t - 1; i++) {
                Slot s = slots[i] = new Slot();
                s.thread = new Thread(() -> loop(s), "guided-" + i);
                s.thread.setDaemon(true);
                s.thread.start();
            }
        }

        void loop(Slot s) {
            int seen = 0;
            while (true) {
                long until = System.nanoTime() + spinNs;
                while (gen == seen) {
                    if (System.nanoTime() < until) Thread.onSpinWait();
                    else {
                        s.parked = true;
                        if (gen == seen) LockSupport.park(this);
                        s.parked = false;
                    }
                }
                seen = gen;
                work();
            }
        }

        void work() {
            int nn = n, g = grain, tt = t;
            int mine = 0;
            while (true) {
                int cur = next.get();
                if (cur >= nn) break;
                int c = Math.max(g, (nn - cur) / (tt * div));
                int s = next.getAndAdd(c);
                if (s >= nn) break;
                int e = Math.min(nn, s + c);
                for (int i = s; i < e; i++) body.accept(i);
                mine += e - s;
            }
            if (mine > 0 && done.addAndGet(mine) == nn) {
                Thread w = waiter;
                if (w != Thread.currentThread()) LockSupport.unpark(w);
            }
        }

        void run(int n, int grain, IntConsumer body) {
            this.n = n;
            this.grain = grain;
            this.body = body;
            this.waiter = Thread.currentThread();
            next.set(0);
            done.set(0);
            gen++;
            for (Slot s : slots) if (s.parked) LockSupport.unpark(s.thread);
            work();
            long until = System.nanoTime() + spinNs;
            while (done.get() != n) {
                if (System.nanoTime() < until) Thread.onSpinWait();
                else LockSupport.parkNanos(this, 50_000);
            }
        }
    }

    // ------------------------------------------------------------------ work
    static volatile long sink;

    static void spin(long ns) {
        long x = 0;
        long end = System.nanoTime() + ns;
        while (System.nanoTime() < end) x++;
        sink += x;
    }

    static IntConsumer workload(long ns, boolean skew) {
        return i -> spin(skew && i % 10 == 0 ? ns * 20 : ns);
    }

    static double time(Runnable fanOut, int reps) {
        for (int i = 0; i < reps / 4; i++) fanOut.run();   // warm-up
        long[] t = new long[reps];
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            fanOut.run();
            t[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(t);
        return t[reps / 2] / 1e3;   // median, us
    }

    public static void main(String[] a) throws Exception {
        int T = a.length > 0 ? Integer.parseInt(a[0]) : 4;
        LabRuntime lab = new LabRuntime(LabRuntime.Config.of("bench", T - 1));
        GuidedPool guided = new GuidedPool(T, 20_000);
        ForkJoinPool fj = new ForkJoinPool(T);
        System.out.printf("threads %d (the caller + %d workers); median wall time of one fan-out, us%n", T, T - 1);
        System.out.printf("%-26s %10s %10s %10s %10s %10s %10s%n", "workload", "serial", "lab 16", "g 20us 4T", "g 1 4T", "g 1 8T", "g 1 16T");
        for (int n : new int[]{64, 512, 4096}) {
            for (long ns : new long[]{300, 2000}) {
                for (boolean skew : new boolean[]{false, true}) {
                    IntConsumer w = workload(ns, skew);
                    int reps = (int) Math.max(200, Math.min(4000, 2_000_000_000L / (n * ns * (skew ? 3 : 1))));
                    double serial = time(() -> { for (int i = 0; i < n; i++) w.accept(i); }, Math.min(reps, 400));
                    double labFixed = time(() -> lab.forRange(n, 16, T - 1, w), reps);
                    int tuned = Math.max(1, n / (T * 8));
                    int grain = Math.max(1, (int) (20_000 / ns));   // ~20 us of work per claim at least
                    guided.div = 4;
                    double gd = time(() -> guided.run(n, grain, w), reps);
                    double g4 = time(() -> guided.run(n, 1, w), reps);
                    guided.div = 8;
                    double g8 = time(() -> guided.run(n, 1, w), reps);
                    guided.div = 16;
                    double g16 = time(() -> guided.run(n, 1, w), reps);
                    System.out.printf("n %5d  %5d ns %-6s %10.1f %10.1f %10.1f %10.1f %10.1f %10.1f%n", n, ns, skew ? "skew" : "even", serial, labFixed, gd, g4, g8, g16);
                }
            }
        }
        // the cost of a near-empty fan-out (wake + barrier): what a frame pays even for tiny work
        IntConsumer tiny = i -> sink++;
        System.out.printf("%nempty-ish fan-out (n=%d, ~0 ns): lab %.1f us, guided %.1f us, forkjoin %.1f us%n", T * 4,
                time(() -> lab.forRange(T * 4, 1, T - 1, tiny), 20000), time(() -> guided.run(T * 4, 1, tiny), 20000),
                time(() -> fj.submit(() -> IntStream.range(0, T * 4).parallel().forEach(tiny)).join(), 20000));
        System.exit(0);
    }
}
