package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;

/**
 * exp/parallel-particles (client): a particle list's ticks spread over the frame pool (FrameReactor's LabRuntime,
 * idle at the client tick: its frame jobs end before it), as vanilla's result.
 *
 * <p>While ParticleEngine.tick runs the client level does not change, and a particle whose class ParticlePurity
 * proves pure touches only itself while it reads the level - so the pure particles of a list are ticked first, on
 * the render thread and the pool's workers together (a shared claim counter over chunks), and then the list is walked
 * in its order as vanilla walks it: the other particles ticked there, every dead one removed and its group counted
 * down. Each pure particle gets exactly the tick it would get; only who runs it changes.
 *
 * <p>Collisions on a worker go through a collide kernel of its own; a block whose shape is not static (it may read a
 * block entity - mod code that is not ours to run on other threads) is asked by the render thread only: a worker
 * finds it in this tick's shared memo or asks the render thread for it and waits (the render thread answers between
 * its own chunks and while it waits for the workers). Off when the collide kernel is off.
 */
public final class ParallelParticles {
    private ParallelParticles() {}

    private static final int MIN = Integer.getInteger("lab.particles.min", 256);
    private static final int CHUNK = Integer.getInteger("lab.particles.chunk", 64);
    public static volatile boolean enabled = true;
    public static long lists, parallel, serial;

    // ------------------------------------------------------------------ the list

    private static Particle[] items = new Particle[1024];
    private static boolean[] pure = new boolean[1024];
    private static int[] pureIdx = new int[1024];

    /** From ParticleEngine.tickParticleList (ParallelParticlesMixin): true if the list was ticked here. */
    public static boolean tickList(ParticleEngine engine, Collection<Particle> list) {
        int n = list.size();
        if (!enabled || n < MIN || !CollideKernel.ON || !CollideKernel.usable()) return false;
        if (items.length < n) {
            items = new Particle[Math.max(n, items.length * 2)];
            pure = new boolean[items.length];
            pureIdx = new int[items.length];
        }
        int i = 0, np = 0;
        for (Particle p : list) {
            items[i] = p;
            boolean ok = ParticlePurity.pure(p.getClass());
            pure[i] = ok;
            if (ok) pureIdx[np++] = i;
            i++;
        }
        if (np < MIN) {
            java.util.Arrays.fill(items, 0, n, null);
            return false;
        }
        lists++;
        parallel += np;
        serial += n - np;
        long t0 = BURST_LOG ? System.nanoTime() : 0;
        long asked0 = ParticleWorkers.requests, pre0 = ParticleWorkers.prefilled, waited0 = ParticleWorkers.WAITED.get();
        if (CHECK) check(np, list);
        else runPure(np, list);
        long t1 = BURST_LOG ? System.nanoTime() : 0;
        dev.lull.opt.ParticleEngineAccessor acc = (dev.lull.opt.ParticleEngineAccessor) engine;
        int dead = 0;
        if (np == n && COMPACT) {
            // every particle pure: nothing runs in the walk, so the dead leave in one pass - the survivors put back in their
            // order, the groups counted down in vanilla's order. Vanilla's Iterator.remove shifts the deque per removal:
            // quadratic when a generation born together dies together (1 400 of 15 000: 27-40 ms)
            int live = 0;
            for (int j = 0; j < n; j++) {
                Particle p = items[j];
                if (p.isAlive()) items[live++] = p;
                else {
                    p.getParticleGroup().ifPresent(g -> acc.sleep$updateCount(g, -1));
                    dead++;
                }
            }
            if (live < n) {
                list.clear();
                for (int j = 0; j < live; j++) list.add(items[j]);
            }
        } else {
            i = 0;
            for (Iterator<Particle> it = list.iterator(); it.hasNext(); i++) {   // vanilla's walk: the others ticked, the dead removed
                Particle p = it.next();
                if (!pure[i]) acc.sleep$tickParticle(p);
                if (!p.isAlive()) {
                    p.getParticleGroup().ifPresent(g -> acc.sleep$updateCount(g, -1));
                    it.remove();
                    dead++;
                }
            }
        }
        java.util.Arrays.fill(items, 0, n, null);
        if (BURST_LOG) {
            long t2 = System.nanoTime();
            if (t2 - t0 > 10_000_000L)
                System.out.printf(java.util.Locale.ROOT, "[sleep] particle burst: list %d (%d pure, %s), parallel %.1f ms (asked %d, prefilled %d; render thread: prefill %.1f ms, own %d chunks %.1f ms, tail %.1f ms; %d helpers waited %.1f ms in all), walk %.1f ms (%d dead)%n",
                        n, np, list.getClass().getSimpleName(), (t1 - t0) / 1e6, ParticleWorkers.requests - asked0, ParticleWorkers.prefilled - pre0,
                        prefillNs / 1e6, ownChunks, ownNs / 1e6, (t1 - t0 - prefillNs - ownNs) / 1e6, helpersRun, (ParticleWorkers.WAITED.get() - waited0) / 1e6, (t2 - t1) / 1e6, dead);
            if (t2 - t0 > 10_000_000L) System.out.println("[sleep] particle burst: " + ages(np));
            else java.util.Arrays.fill(CHUNK_NS, 0, Math.min(CHUNK_NS.length, (np + CHUNK - 1) / CHUNK), 0);
        }
        return true;
    }

    /** -Dlab.particles.compact=false: the dead removed one by one as vanilla does (A/B) */
    private static final boolean COMPACT = !"false".equals(System.getProperty("lab.particles.compact"));

    private static long prefillNs, ownNs;
    /** the burst log's time of each chunk by its index (the newest particles are the list's last) */
    private static final long[] CHUNK_NS = new long[1024];

    /** us per particle in the list's newest quarter (its last chunks) and in the rest */
    private static String ages(int np) {
        int chunks = Math.min(CHUNK_NS.length, (np + CHUNK - 1) / CHUNK), q = Math.max(1, chunks / 4);
        long newer = 0, older = 0;
        for (int c = 0; c < chunks; c++) if (c >= chunks - q) newer += CHUNK_NS[c]; else older += CHUNK_NS[c];
        java.util.Arrays.fill(CHUNK_NS, 0, chunks, 0);
        return String.format(java.util.Locale.ROOT, "us per particle: newest quarter %.1f, the rest %.1f",
                newer / 1e3 / Math.max(1, Math.min(np, q * CHUNK)), older / 1e3 / Math.max(1, np - q * CHUNK));
    }
    private static int ownChunks, helpersRun;

    /** -Dlab.particles.burstLog=true: a list tick over 10 ms printed with its parts */
    private static final boolean BURST_LOG = Boolean.getBoolean("lab.particles.burstLog");

    private static final AtomicInteger NEXT = new AtomicInteger(), REMAINING = new AtomicInteger();
    private static volatile Throwable error;
    private static volatile Particle failed;
    private static int jobN;

    /**
     * -Dlab.particles.serveOnly=N: the render thread as the reactor - it hands out the list, prefills and answers the
     * workers' questions at once (awake until the list is done), ticks no particle itself: -1 every list, N &gt; 0 a
     * list that asked more than N shapes in its last tick, 0 never
     */
    private static final int SERVE_ONLY = Integer.getInteger("lab.particles.serveOnly", 0);
    /** -Dlab.particles.seat=false: no seat thread in the reactor's place (the pool's workers only) */
    private static final boolean SEAT = !"false".equals(System.getProperty("lab.particles.seat"));
    public static long reactorTicks;
    private static Thread seatThread;
    private static volatile boolean seatGo;

    /**
     * The seat: one thread of its own that ticks particles while the render thread is the reactor (the frame pool keeps
     * min(4, cores - 2) workers for all its users - 2 on a 4-core machine). Parked between particle ticks.
     */
    private static void seat() {
        Thread t = seatThread;
        if (t == null) {
            t = new Thread(() -> {
                while (true) {
                    while (!seatGo) LockSupport.park(ParallelParticles.class);
                    seatGo = false;
                    workerRun();
                }
            }, "Particle-Seat");
            t.setDaemon(true);
            seatThread = t;
            t.start();
        }
        seatGo = true;
        LockSupport.unpark(t);
    }
    private static final java.util.Map<Object, Integer> ASKED = new java.util.IdentityHashMap<>();

    private static void runPure(int np, Object list) {
        long askedAtStart = ParticleWorkers.requests;
        ParticleWorkers.renderThread = Thread.currentThread();
        LabRuntime pool = FrameReactor.pool();
        jobN = np;
        NEXT.set(0);
        REMAINING.set(np);
        error = null;
        ParticleWorkers.SHARED.clear();
        ParticleWorkers.active = true;
        try {
            int chunks = (np + CHUNK - 1) / CHUNK;
            // the reactor: the render thread hands out the list, prefills and answers the workers' questions at once - awake
            // for the particle tick, ticking no particle itself; its place taken by the seat
            Integer lastAsked = ASKED.get(list);
            boolean reactor = chunks > 1 && (SERVE_ONLY < 0 || SERVE_ONLY > 0 && lastAsked != null && lastAsked > SERVE_ONLY);
            int helpers = Math.min(pool.workerCount(), reactor ? chunks : chunks - 1);
            for (int k = 0; k < helpers; k++) pool.execute(ParallelParticles::workerRun);
            if (reactor) {
                reactorTicks++;
                if (SEAT && chunks > helpers) seat();
            }
            var level = net.minecraft.client.Minecraft.getInstance().level;
            long b0 = BURST_LOG ? System.nanoTime() : 0;
            if (level != null) ParticleWorkers.prefill(level, list);   // while the workers start: this list's last tick's shapes, in this tick's level
            long b1 = BURST_LOG ? System.nanoTime() : 0;
            ownChunks = 0;
            if (!reactor) drain(true);
            long b2 = BURST_LOG ? System.nanoTime() : 0;
            if (BURST_LOG) {
                prefillNs = b1 - b0;
                ownNs = b2 - b1;
                helpersRun = helpers;
            }
            long spinUntil = System.nanoTime() + 20_000;
            while (REMAINING.get() != 0) {
                ParticleWorkers.serve();
                if (REMAINING.get() == 0) break;
                if (reactor || System.nanoTime() < spinUntil) Thread.onSpinWait();   // the reactor stays awake until the list is done
                else LockSupport.park(ParallelParticles.class);   // a worker's question or the last chunk unparks
            }
            ParticleWorkers.serve();
            ParticleWorkers.keepDemand(list);
            ASKED.put(list, (int) (ParticleWorkers.requests - askedAtStart));
        } finally {
            ParticleWorkers.active = false;
        }
        Throwable t = error;
        if (t != null) {
            CrashReport report = CrashReport.forThrowable(t, "Ticking Particle");
            Particle f = failed;
            report.addCategory("Particle being ticked").setDetail("Particle", () -> String.valueOf(f));
            throw new ReportedException(report);
        }
    }

    private static void workerRun() {
        boolean[] in = ParticleWorkers.IN_WORKER.get();
        in[0] = true;
        try {
            drain(false);
        } finally {
            in[0] = false;
        }
    }

    private static void drain(boolean render) {
        int done = 0;
        for (int s; (s = NEXT.getAndAdd(CHUNK)) < jobN; ) {
            int e = Math.min(jobN, s + CHUNK);
            long c0 = BURST_LOG ? System.nanoTime() : 0;
            for (int j = s; j < e; j++) {
                Particle p = items[pureIdx[j]];
                try {
                    p.tick();
                } catch (Throwable t) {
                    error = t;
                    failed = p;
                }
                if (render && ParticleWorkers.pending()) ParticleWorkers.serve();   // a waiting worker answered now, not after the chunk
            }
            if (BURST_LOG && s / CHUNK < CHUNK_NS.length) CHUNK_NS[s / CHUNK] = System.nanoTime() - c0;
            done += e - s;
            if (render) {
                ParticleWorkers.serve();
                ownChunks++;
            }
        }
        if (done > 0 && REMAINING.addAndGet(-done) == 0 && !render) LockSupport.unpark(ParticleWorkers.renderThread);
    }

    // ------------------------------------------------------------------ check: parallel against the render thread, field by field

    /** -Dlab.particles.check=true: every pure particle's tick done in parallel, kept, its state put back and ticked again here; compared */
    private static final boolean CHECK = Boolean.getBoolean("lab.particles.check");
    public static long checked, mismatches, unchecked;
    private static final java.util.Map<Class<?>, java.lang.reflect.Field[]> FIELDS = new java.util.HashMap<>();

    private static java.lang.reflect.Field[] fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            java.util.List<java.lang.reflect.Field> l = new java.util.ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass())
                for (java.lang.reflect.Field f : x.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        l.add(f);
                    } catch (RuntimeException e) {
                        // a field we cannot read: compared without it
                    }
                }
            return l.toArray(new java.lang.reflect.Field[0]);
        });
    }

    /** the random source's state (LegacyRandomSource / SingleThreadedRandomSource seed), or null if of another kind */
    private static java.lang.reflect.Field seedField(Object random) {
        if (random == null) return null;
        for (Class<?> x = random.getClass(); x != null && x != Object.class; x = x.getSuperclass())
            for (java.lang.reflect.Field f : x.getDeclaredFields())
                if (f.getName().equals("seed") && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
        return null;
    }

    private static Object[] snapshot(Particle p) throws ReflectiveOperationException {
        java.lang.reflect.Field[] fs = fields(p.getClass());
        Object[] v = new Object[fs.length + 1];
        for (int i = 0; i < fs.length; i++) v[i] = fs[i].get(p);
        Object rnd = fieldValue(p, "random");
        java.lang.reflect.Field sf = seedField(rnd);
        if (sf != null) {
            Object seed = sf.get(rnd);
            v[fs.length] = seed instanceof java.util.concurrent.atomic.AtomicLong a ? (Object) a.get() : seed;
        }
        return v;
    }

    private static void restore(Particle p, Object[] v) throws ReflectiveOperationException {
        java.lang.reflect.Field[] fs = fields(p.getClass());
        for (int i = 0; i < fs.length; i++) fs[i].set(p, v[i]);
        Object rnd = fieldValue(p, "random");
        java.lang.reflect.Field sf = seedField(rnd);
        if (sf != null) {
            Object seed = sf.get(rnd);
            if (seed instanceof java.util.concurrent.atomic.AtomicLong a) a.set((Long) v[fs.length]);
            else sf.set(rnd, v[fs.length]);
        }
    }

    private static Object fieldValue(Particle p, String name) throws ReflectiveOperationException {
        for (java.lang.reflect.Field f : fields(p.getClass())) if (f.getName().equals(name)) return f.get(p);
        return null;
    }

    private static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a instanceof Double x && b instanceof Double y) return Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(y);
        if (a instanceof Float x && b instanceof Float y) return Float.floatToRawIntBits(x) == Float.floatToRawIntBits(y);
        if (a instanceof net.minecraft.world.phys.AABB x && b instanceof net.minecraft.world.phys.AABB y)
            return same(x.minX, y.minX) && same(x.minY, y.minY) && same(x.minZ, y.minZ) && same(x.maxX, y.maxX) && same(x.maxY, y.maxY) && same(x.maxZ, y.maxZ);
        return a != null && a.equals(b);
    }

    private static void check(int np, Object list) {
        Object[][] before = new Object[np][], par = new Object[np][];
        try {
            for (int j = 0; j < np; j++) before[j] = snapshot(items[pureIdx[j]]);
            runPure(np, list);
            for (int j = 0; j < np; j++) par[j] = snapshot(items[pureIdx[j]]);
            for (int j = 0; j < np; j++) {
                Particle p = items[pureIdx[j]];
                restore(p, before[j]);
                p.tick();   // here, on the render thread, as vanilla does
                Object[] ser = snapshot(p);
                java.lang.reflect.Field[] fs = fields(p.getClass());
                boolean ok = true;
                for (int k = 0; k < ser.length; k++)
                    if (!same(ser[k], par[j][k])) {
                        ok = false;
                        if (mismatches < 10)
                            System.out.println("[sleep/opt] parallel particles MISMATCH " + p.getClass().getSimpleName() + "." + (k < fs.length ? fs[k].getName() : "random seed")
                                    + ": parallel " + par[j][k] + ", render thread " + ser[k]);
                    }
                if (before[j][fs.length] == null) unchecked++;
                checked++;
                if (!ok) mismatches++;
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "parallel particles: lists %d (reactor %d), particles in parallel %d, on the render thread %d, shapes asked for workers %d, prefilled %d; classes pure %d, not %d%s",
                lists, reactorTicks, parallel, serial, ParticleWorkers.requests, ParticleWorkers.prefilled, ParticlePurity.pure, ParticlePurity.impure,
                CHECK ? String.format(java.util.Locale.ROOT, "; checked %d (random not compared %d), MISMATCHES %d", checked, unchecked, mismatches) : "");
    }
}
