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
        if (CHECK) check(np, list);
        else runPure(np, list);
        dev.lull.opt.ParticleEngineAccessor acc = (dev.lull.opt.ParticleEngineAccessor) engine;
        i = 0;
        for (Iterator<Particle> it = list.iterator(); it.hasNext(); i++) {   // vanilla's walk: the others ticked, the dead removed
            Particle p = it.next();
            if (!pure[i]) acc.sleep$tickParticle(p);
            if (!p.isAlive()) {
                p.getParticleGroup().ifPresent(g -> acc.sleep$updateCount(g, -1));
                it.remove();
            }
        }
        java.util.Arrays.fill(items, 0, n, null);
        return true;
    }

    private static final AtomicInteger NEXT = new AtomicInteger(), REMAINING = new AtomicInteger();
    private static volatile Throwable error;
    private static volatile Particle failed;
    private static int jobN;

    private static void runPure(int np, Object list) {
        ParticleWorkers.renderThread = Thread.currentThread();
        LabRuntime pool = FrameReactor.pool();
        jobN = np;
        NEXT.set(0);
        REMAINING.set(np);
        error = null;
        ParticleWorkers.SHARED.clear();
        ParticleWorkers.active = true;
        try {
            int helpers = Math.min(pool.workerCount(), (np + CHUNK - 1) / CHUNK - 1);
            for (int k = 0; k < helpers; k++) pool.execute(ParallelParticles::workerRun);
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level != null) ParticleWorkers.prefill(level, list);   // while the workers start: this list's last tick's shapes, in this tick's level
            drain(true);
            long spinUntil = System.nanoTime() + 20_000;
            while (REMAINING.get() != 0) {
                ParticleWorkers.serve();
                if (REMAINING.get() == 0) break;
                if (System.nanoTime() < spinUntil) Thread.onSpinWait();
                else LockSupport.park(ParallelParticles.class);   // a worker's question or the last chunk unparks
            }
            ParticleWorkers.serve();
            ParticleWorkers.keepDemand(list);
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
            for (int j = s; j < e; j++) {
                Particle p = items[pureIdx[j]];
                try {
                    p.tick();
                } catch (Throwable t) {
                    error = t;
                    failed = p;
                }
            }
            done += e - s;
            if (render) ParticleWorkers.serve();
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
        return String.format(java.util.Locale.ROOT, "parallel particles: lists %d, particles in parallel %d, on the render thread %d, shapes asked for workers %d, prefilled %d; classes pure %d, not %d%s",
                lists, parallel, serial, ParticleWorkers.requests, ParticleWorkers.prefilled, ParticlePurity.pure, ParticlePurity.impure,
                CHECK ? String.format(java.util.Locale.ROOT, "; checked %d (random not compared %d), MISMATCHES %d", checked, unchecked, mismatches) : "");
    }
}
