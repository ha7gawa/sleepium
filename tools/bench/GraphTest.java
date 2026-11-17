import dev.lab.runtime.LabRuntime;
import dev.lab.runtime.TaskGraph;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * TaskGraph's standalone check: order, completeness, errors, repeated runs, cost; a watchdog reports a hang.
 * java -cp build/classes/java/main GraphTest.java
 */
public class GraphTest {
    static final AtomicLong SEQ = new AtomicLong();
    static volatile long lastProgress = System.nanoTime();
    static volatile String phase = "start";

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] a) throws Exception {
        Thread main = Thread.currentThread();
        Thread dog = new Thread(() -> {
            while (true) {
                LockSupport.parkNanos(1_000_000_000L);
                if (System.nanoTime() - lastProgress > 5_000_000_000L) {
                    System.out.println("HANG in " + phase);
                    for (StackTraceElement e : main.getStackTrace()) System.out.println("  " + e);
                    System.exit(2);
                }
            }
        });
        dog.setDaemon(true);
        dog.start();
        LabRuntime pool = new LabRuntime(LabRuntime.Config.of("Test-Worker", 4));
        TaskGraph.Member me = new TaskGraph.Member("main", TaskGraph.UNPARK);
        me.bind(main);
        Runnable sleep = () -> LockSupport.parkNanos(1_000_000L);

        // 1. diamond: A(any) -> B(owner), C(any) -> D(owner)
        phase = "diamond";
        long[] at = new long[4];
        TaskGraph g = new TaskGraph("diamond", pool);
        TaskGraph.Node A = g.add("A", LabRuntime.Lane.TICK, () -> at[0] = SEQ.incrementAndGet());
        TaskGraph.Node B = g.add("B", me, () -> { check(Thread.currentThread() == main, "B on its owner"); at[1] = SEQ.incrementAndGet(); });
        TaskGraph.Node C = g.add("C", LabRuntime.Lane.CHUNK, () -> at[2] = SEQ.incrementAndGet());
        TaskGraph.Node D = g.add("D", me, () -> { check(Thread.currentThread() == main, "D on its owner"); at[3] = SEQ.incrementAndGet(); });
        g.after(A, B).after(A, C).after(B, D).after(C, D).seal();
        int runs = 200_000;
        long t0 = System.nanoTime();
        for (int r = 0; r < runs; r++) {
            g.start();
            g.await(me, sleep);
            check(at[0] < at[1] && at[0] < at[2] && at[1] < at[3] && at[2] < at[3], "diamond order " + java.util.Arrays.toString(at));
            if ((r & 1023) == 0) lastProgress = System.nanoTime();
        }
        System.out.printf("diamond: %d runs ok, %.2f us a run; helped %d, waits %d%n", runs, (System.nanoTime() - t0) / 1e3 / runs, me.helped, me.waits);

        // 2. fan-out: root(owner) -> 64 any -> join(owner)
        phase = "fan";
        TaskGraph f = new TaskGraph("fan", pool);
        AtomicLong done = new AtomicLong();
        TaskGraph.Node root = f.add("root", me, () -> {});
        TaskGraph.Node join = f.add("join", me, () -> check(done.get() % 64 == 0, "join after all 64: " + done.get()));
        for (int i = 0; i < 64; i++) {
            TaskGraph.Node w = f.add("w" + i, LabRuntime.Lane.TICK, () -> {
                long x = 0;
                for (int k = 0; k < 2000; k++) x += k * 31L;   // a little work
                if (x == 42) System.out.println();
                done.incrementAndGet();
            });
            f.after(root, w).after(w, join);
        }
        f.seal();
        runs = 20_000;
        t0 = System.nanoTime();
        for (int r = 0; r < runs; r++) {
            f.start();
            f.await(me, sleep);
            if ((r & 255) == 0) lastProgress = System.nanoTime();
        }
        check(done.get() == 64L * runs, "fan count " + done.get());
        System.out.printf("fan-out 64: %d runs ok, %.2f us a run; helped %d%n", runs, (System.nanoTime() - t0) / 1e3 / runs, me.helped);

        // 3. chain alternating owner / any, 16 long
        phase = "chain";
        TaskGraph c = new TaskGraph("chain", pool);
        long[] order = new long[16];
        TaskGraph.Node prev = null;
        for (int i = 0; i < 16; i++) {
            final int k = i;
            TaskGraph.Node n = (i % 2 == 0) ? c.add("c" + i, me, () -> order[k] = SEQ.incrementAndGet()) : c.add("c" + i, LabRuntime.Lane.CHUNK, () -> order[k] = SEQ.incrementAndGet());
            if (prev != null) c.after(prev, n);
            prev = n;
        }
        c.seal();
        runs = 50_000;
        t0 = System.nanoTime();
        for (int r = 0; r < runs; r++) {
            c.start();
            c.await(me, sleep);
            for (int i = 1; i < 16; i++) check(order[i - 1] < order[i], "chain order at " + i);
            if ((r & 1023) == 0) lastProgress = System.nanoTime();
        }
        System.out.printf("chain 16: %d runs ok, %.2f us a run%n", runs, (System.nanoTime() - t0) / 1e3 / runs);

        // 4. an error: the run ends, await throws, the graph runs again
        phase = "error";
        TaskGraph e = new TaskGraph("err", pool);
        long[] after = new long[1];
        TaskGraph.Node bad = e.add("bad", LabRuntime.Lane.TICK, () -> { throw new IllegalStateException("boom"); });
        TaskGraph.Node next = e.add("next", me, () -> after[0]++);
        e.after(bad, next).seal();
        int thrown = 0;
        for (int r = 0; r < 1000; r++) {
            e.start();
            try {
                e.await(me, sleep);
            } catch (IllegalStateException x) {
                thrown++;
            }
        }
        check(thrown == 1000 && after[0] == 1000, "errors " + thrown + ", successors " + after[0]);
        System.out.println("errors: 1000 runs, each thrown at await, successors still ran");

        // 5. a member woken from a real sleep: the owner node posted while the member is parked long
        phase = "wake";
        TaskGraph w = new TaskGraph("wake", pool);
        TaskGraph.Node slow = w.add("slow", LabRuntime.Lane.BACKGROUND, () -> { long u = System.nanoTime() + 200_000L; while (System.nanoTime() < u) Thread.onSpinWait(); });
        TaskGraph.Node mine = w.add("mine", me, () -> {});
        w.after(slow, mine).seal();
        runs = 2_000;
        t0 = System.nanoTime();
        long worst = 0;
        for (int r = 0; r < runs; r++) {
            long s0 = System.nanoTime();
            w.start();
            w.await(me, () -> LockSupport.parkNanos(50_000_000L));   // a long sleep: only a wake ends it early
            worst = Math.max(worst, System.nanoTime() - s0);
            lastProgress = System.nanoTime();
        }
        System.out.printf("wake: the worst run %.2f ms%n", worst / 1e6);
        System.out.printf("wake: %d runs, %.1f us a run (a sleep of 50 ms would show if a wake were lost)%n", runs, (System.nanoTime() - t0) / 1e3 / runs);
        System.out.println(pool.stats());
        pool.shutdown();
        System.out.println("ALL OK");
    }
}
