package dev.lull.optsupport;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.prender.tickPoses=true (plan B, 11-15; not exact - an opt-in): an entity's model-part records made once per
 * tick instead of once per frame. Right after a tick the renderer runs twice on a worker, at partial tick 0 and 1, into
 * two record sets (camera at the entity's position: the records relative to it); every frame until the next tick takes
 * the two sets and interpolates them per part at the frame's partial tick - translation and scale linearly, the
 * rotation by normalised quaternion lerp - and moves them to the camera. The renderer itself no longer runs per frame;
 * the frame's job for the entity (on a worker, as ParallelRender's) is the interpolation.
 *
 * <p>Vanilla computes a pose as a function of the partial tick (walk cycle cosines, head turns, ...); for most models
 * it is close to linear over one tick, for some not. Whether an entity type qualifies is observed, not listed: its
 * first captures also render at 0.5 and compare that with the interpolation; a type qualifies when the difference stays
 * under {@link #TOLERANCE} blocks (a part's unit box corner) in all but one of {@link #SAMPLES} samples, and is checked
 * again every {@link #RECHECK}th capture afterwards (dropped when it starts to differ). Entities with output other than
 * model parts (vertex bytes, side effects), burning ones, and ticks whose two sets differ in structure render as before.
 *
 * <p>The pairs map: render thread. A pair's renders and its evaluation: one worker task (the type's statistics under
 * the type's lock). The interpolation: the entity's frame job, any worker.
 */
public final class TickPoses {
    private TickPoses() {}

    public static final boolean ON = Boolean.getBoolean("lab.prender.tickPoses");
    /** ClientAuto's in-window flip */
    public static boolean enabled = true;
    static final double TOLERANCE = Double.parseDouble(System.getProperty("lab.prender.tickPoses.tolerance", "0.04"));
    static final int SAMPLES = Integer.getInteger("lab.prender.tickPoses.samples", 48), RECHECK = 32;
    /**
     * Keys a tick: 3 (default) - poses at 0, 0.5 and 1, interpolated piecewise (a walk cycle's cosine bends within a tick:
     * the middle key quarters the error); 2 - at 0 and 1. The check renders between keys (0.25, or 0.5 with 2 keys).
     */
    static final int KEYS = Integer.getInteger("lab.prender.tickPoses.keys", 3);
    /** the jobs: 0 at 0, 1 at 1, 2 at 0.5 (a key with 3 keys, else the check), 3 at 0.25 (the check with 3 keys) */
    private static final float[] PTS = {0f, 1f, 0.5f, 0.25f};
    private static final int CHECK_JOB = KEYS == 3 ? 3 : 2;
    /**
     * -Dlab.prender.tickPoses.gpu (default on, 3 keys): the keys decomposed once a tick on the worker (rotation, scale,
     * translation each) into 288-byte records the part shader interpolates (PartBatch mode 8) - a frame copies them; no
     * interpolation on the CPU. Vanilla model parts only (GeckoLib bones and items: the CPU interpolation).
     */
    static final boolean GPU = KEYS == 3 && !"false".equals(System.getProperty("lab.prender.tickPoses.gpu"));
    static final AtomicLong GPU_FRAMES = new AtomicLong(), GPU_REFUSED = new AtomicLong();

    public static long captures;
    static final AtomicLong MERGED = new AtomicLong(), FALLBACKS = new AtomicLong(), STRUCTURAL = new AtomicLong(), CHECKS = new AtomicLong(),
            CHECK_FAILS = new AtomicLong(), NOT_PARTS = new AtomicLong(), INTERP_NS = new AtomicLong(), INTERP_REC = new AtomicLong();

    /** per type: 0 observing, 1 qualifies, 2 does not */
    static final class Stat {
        final EntityType<?> type;
        volatile int state;
        int samples, bad, recheckN, recheckBad, since, notParts;
        double worst;
        final double[] err = new double[64];
        int errN;

        /** the samples' errors: count, median, 90th percentile, worst (blocks) */
        String errors() {
            double[] v = java.util.Arrays.copyOf(err, errN);
            java.util.Arrays.sort(v);
            return String.format(java.util.Locale.ROOT, "%d samples (%d over %.3f), error median %.4f, p90 %.4f, worst %.4f blocks",
                    samples, bad, TOLERANCE, v.length == 0 ? 0 : v[v.length / 2], v.length == 0 ? 0 : v[(int) (v.length * 0.9)], worst);
        }

        Stat(EntityType<?> type) {
            this.type = type;
        }
    }

    private static final Map<EntityType<?>, Stat> STATS = new IdentityHashMap<>();
    private static final Map<Entity, Pair> PAIRS = new IdentityHashMap<>();

    /** One entity's records for the current tick: jobs at 0, 1 (and 0.5 when checked), rendered one after another on one worker. */
    static final class Pair implements Runnable {
        final ParallelRender.Job[] jobs = {new ParallelRender.Job(), new ParallelRender.Job(), new ParallelRender.Job(), new ParallelRender.Job()};
        Stat stat;
        long stamp;
        boolean check;
        /** the two sets usable for frames (after the run) */
        volatile boolean usable;
        double ax, ay, az;
        volatile boolean done;
        final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        long frameUsed;
        /** a scratch record set for the check (this pair's task only) */
        long mid;
        int midCap;
        /** GPU: the keys as mode-8 records (288 bytes each), valid when gpuOk */
        long gpu;
        int gpuCap;
        volatile boolean gpuOk;

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            ParallelRender.Set set = ParallelRender.takeSet();
            try {
                int n = KEYS + (check ? 1 : 0);
                for (int i = 0; i < n; i++) {
                    ParallelRender.Job j = jobs[i];
                    j.forcedSet = set;
                    j.run();
                    if (!j.ok) break;
                }
                usable = set != null && evaluate(this);
                gpuOk = usable && GPU && buildGpu(this);
            } catch (Throwable t) {
                usable = false;
            } finally {
                for (ParallelRender.Job j : jobs) j.forcedSet = null;
                ParallelRender.giveSet(set);
                done = true;
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        void await() {
            run();   // (not taken yet: whoever waits renders it itself)
            if (done) return;
            synchronized (this) {
                while (!done) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        void free() {
            for (ParallelRender.Job j : jobs) j.parts.free();
            if (gpu != 0) MemoryUtil.nmemFree(gpu);
            gpu = 0;
            gpuCap = 0;
            if (mid != 0) MemoryUtil.nmemFree(mid);
            mid = 0;
            midCap = 0;
        }
    }

    /** an entity's state as the renderer sees it between two ticks */
    private static long stamp(Entity e) {
        long h = e.tickCount;
        h = h * 31 + Double.doubleToLongBits(e.getX());
        h = h * 31 + Double.doubleToLongBits(e.getY());
        h = h * 31 + Double.doubleToLongBits(e.getZ());
        h = h * 31 + Double.doubleToLongBits(e.xOld);
        h = h * 31 + Double.doubleToLongBits(e.yOld);
        h = h * 31 + Double.doubleToLongBits(e.zOld);
        h = h * 31 + Float.floatToIntBits(e.getYRot());
        h = h * 31 + Float.floatToIntBits(e.yRotO);
        h = h * 31 + Float.floatToIntBits(e.getXRot());
        return h * 31 + Float.floatToIntBits(e.xRotO);
    }

    private static long frame;

    /** ParallelRender.drain's end: pairs of entities not drawn for a while (gone, out of view) freed */
    static void endFrame() {
        if ((++frame & 255) != 0 || PAIRS.isEmpty()) return;
        for (Iterator<Map.Entry<Entity, Pair>> it = PAIRS.entrySet().iterator(); it.hasNext(); ) {
            Pair p = it.next().getValue();
            if (frame - p.frameUsed > 600 && (p.done || p.claimed.compareAndSet(false, true))) {
                runPrep(p);   // (asked once: dropped, the parts would stay the CPU way)
                p.free();
                it.remove();
            }
        }
    }

    /**
     * The frame's entity queue (ParallelRender.enqueue, an entity going to the workers): the pair this frame's job
     * interpolates, or null (the usual job). A new tick's pair is started on the pool here.
     */
    static Pair pairFor(Entity e, java.util.concurrent.Executor pool) {
        // (parts the CPU way, or a measured choice running - PartsTune, InstTune, CacheTune: nothing to capture, nothing to disturb)
        if (!enabled || !PartBatch.partsInJobs || ParallelRender.measuring() || PartBatch.InstTune.measuring()) return null;   // (burning: the flame drawn per frame by ParallelRender.shadow)
        Stat st = STATS.computeIfAbsent(e.getType(), Stat::new);
        if (st.state == 2) return null;
        long s = stamp(e);
        Pair p = PAIRS.get(e);
        if (p != null && p.done) runPrep(p);
        if (p != null && p.stamp == s) {
            p.frameUsed = frame;
            if (st.state != 1) return null;
            return !p.done || p.usable ? p : null;   // (still rendering: the job waits for it)
        }
        if (p == null) {
            p = new Pair();
            PAIRS.put(e, p);
        } else if (!p.done && !p.claimed.compareAndSet(false, true)) return null;   // the old one still rendering: next frame (else taken back, not run)
        p.stat = st;
        p.stamp = s;
        p.frameUsed = frame;
        p.check = st.state == 0 || ++st.recheckN % RECHECK == 0;
        p.usable = false;
        p.done = false;
        p.ax = e.getX();
        p.ay = e.getY();
        p.az = e.getZ();
        for (int i = 0; i < 4; i++) {
            ParallelRender.Job j = p.jobs[i];
            j.entity = e;
            j.camX = p.ax;
            j.camY = p.ay;
            j.camZ = p.az;
            j.pt = PTS[i];
            j.rendererOnly = true;
            j.tick = null;
            j.done = false;
            j.ok = false;
            j.claimed.set(false);
        }
        p.claimed.set(false);
        captures++;
        pool.execute(p);
        return st.state == 1 ? p : null;
    }

    /** a finished capture's preparations (types learnt, geometry compiled), on the render thread; while there were some, the pair is not used */
    static void runPrep(Pair p) {
        for (ParallelRender.Job j : p.jobs) {
            if (j.prep.isEmpty()) continue;
            for (int i = 0; i < j.prep.size(); i++) j.prep.get(i).execute();
            PREPARED.addAndGet(j.prep.size());
            j.prep.clear();
        }
    }

    static final AtomicLong PREPARED = new AtomicLong();

    /** structure the same: record count, types, geometry, kinds */
    private static boolean sameShape(PartBatch.JobParts a, PartBatch.JobParts b) {
        if (a.n != b.n) return false;
        for (int i = 0; i < a.n; i++)
            if (a.types[i] != b.types[i] || a.first[i] != b.first[i] || a.quads[i] != b.quads[i] || a.kind[i] != b.kind[i]) return false;
        return true;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");

    private static String shapeDiff(PartBatch.JobParts a, PartBatch.JobParts b) {
        StringBuilder w = new StringBuilder("records " + a.n + " / " + b.n);
        for (int i = 0; i < Math.min(a.n, b.n); i++)
            if (a.types[i] != b.types[i] || a.first[i] != b.first[i] || a.quads[i] != b.quads[i] || a.kind[i] != b.kind[i]) {
                w.append(", first difference at ").append(i).append(": ").append(a.types[i]).append(' ').append(a.first[i]).append('/').append(a.quads[i]).append(" k").append(a.kind[i])
                        .append(" vs ").append(b.types[i]).append(' ').append(b.first[i]).append('/').append(b.quads[i]).append(" k").append(b.kind[i]);
                break;
            }
        return w.toString();
    }

    /** a capture is usable: the job fine, model parts only (no vertex bytes, no side effects) */
    private static boolean partsOnly(ParallelRender.Job j) {
        return j.ok && j.out.size == 0 && j.out.types.isEmpty() && j.calls.isEmpty() && j.parts.n > 0;
    }

    /** the pair's task, after its renders: whether frames may use it; the type's statistics */
    private static boolean evaluate(Pair p) {
        ParallelRender.Job a = p.jobs[0], b = p.jobs[1], m = p.jobs[2], h = p.jobs[CHECK_JOB];
        Stat st = p.stat;
        for (int k = 0; k < KEYS; k++) {
            ParallelRender.Job x = p.jobs[k];
            if (!x.ok || !x.prep.isEmpty() || x.parts.n == 0) return false;   // (parts not captured yet - the type or geometry being learnt: not the type's fault)
        }
        if (p.check && !h.prep.isEmpty()) return false;
        if (!partsOnly(a) || !partsOnly(b) || KEYS == 3 && !partsOnly(m)) {
            if (NOT_PARTS.incrementAndGet() <= 3) {
                ParallelRender.Job x = !partsOnly(a) ? a : !partsOnly(b) ? b : m;
                StringBuilder w = new StringBuilder();
                for (int i = 0; i < x.out.types.size(); i++) w.append(' ').append(x.out.types.get(i)).append(" x").append(i < x.out.counts.length ? x.out.counts[i] : -1);
                LOG.info("[sleep/opt] tick poses: " + st.type + " not parts only: bytes " + x.out.size + ", calls " + x.calls.size() + ", parts " + x.parts.n + ", segments" + w);
            }
            synchronized (st) {   // (several in a row: an armour layer, a name - the type's own output, not a passing state)
                if (st.state == 0 && ++st.notParts >= 6 && st.notParts * 2 > st.samples + st.notParts) decide(st, 2, "output besides model parts (" + st.notParts + " captures)");
            }
            return false;
        }
        if (!sameShape(a.parts, b.parts) || KEYS == 3 && !sameShape(a.parts, m.parts)) {
            if (STRUCTURAL.incrementAndGet() <= 4) LOG.info("[sleep/opt] tick poses: " + st.type + " sets differ: " + shapeDiff(a.parts, sameShape(a.parts, b.parts) ? m.parts : b.parts));
            return false;
        }
        if (!p.check || !partsOnly(h) || !sameShape(a.parts, h.parts)) return true;
        CHECKS.incrementAndGet();
        int n = a.parts.n;
        if (p.midCap < n) {
            if (p.mid != 0) MemoryUtil.nmemFree(p.mid);
            p.midCap = Math.max(n, 2 * p.midCap);
            p.mid = MemoryUtil.nmemAlloc(144L * p.midCap);
        }
        sample(p, PTS[CHECK_JOB], p.mid, 0f, 0f, 0f);
        double err = 0;
        int worstAt = 0;
        for (int i = 0; i < n; i++) {
            double d = distance(p.mid + 144L * i, h.parts.mem + 144L * i);
            if (d > err) {
                err = d;
                worstAt = i;
            }
        }
        if (err > 5 * TOLERANCE && BIG.incrementAndGet() <= 6)
            LOG.info("[sleep/opt] tick poses: " + st.type + " check off by " + String.format(java.util.Locale.ROOT, "%.4f", err) + " blocks at record " + worstAt + " of " + n
                    + " (translation " + String.format(java.util.Locale.ROOT, "%.4f", tdist(p.mid + 144L * worstAt, h.parts.mem + 144L * worstAt)) + ")");
        boolean bad = err > TOLERANCE;
        if (bad) CHECK_FAILS.incrementAndGet();
        synchronized (st) {
            st.worst = Math.max(st.worst, err);
            if (st.state == 0) {
                st.samples++;
                if (bad) st.bad++;
                if (st.errN < st.err.length) st.err[st.errN++] = err;
                // one sample over the tolerance allowed (a snap: a head turned past 180), none over 5 times it
                if (st.bad > 1 || err > 5 * TOLERANCE) decide(st, 2, "differs: " + st.errors());
                else if (st.samples >= SAMPLES) decide(st, 1, st.errors());
            } else if (st.state == 1) {
                st.since++;
                boolean far = err > 2 * TOLERANCE;   // (a recheck counts against the type only when well over: values near the line are noise)
                if (far) st.recheckBad++;   // (rare snaps - an attack's start, a hurt jolt - allowed: more than 3 % of 64+ rechecks drop the type)
                if (far && st.since >= 64 && st.recheckBad * 100 > 3 * st.since)
                    decide(st, 2, String.format(java.util.Locale.ROOT, "began to differ (worst %.4f blocks, %d of %d rechecks)", st.worst, st.recheckBad, st.since));
            }
        }
        return true;
    }

    private static void decide(Stat st, int state, String why) {
        st.state = state;
        LOG.info("[sleep/opt] tick poses: " + st.type + (state == 1 ? " qualifies: " : " stays per frame: ") + why);
    }

    /** the largest distance a corner of the part's unit box moves between two records' poses (blocks) */
    private static double distance(long a, long b) {
        double t = 0, c = 0;
        for (int k = 0; k < 3; k++) t = Math.max(t, Math.abs(MemoryUtil.memGetFloat(a + 48 + 4 * k) - MemoryUtil.memGetFloat(b + 48 + 4 * k)));
        for (int col = 0; col < 3; col++) {
            double s = 0;
            for (int k = 0; k < 3; k++) s += Math.abs(MemoryUtil.memGetFloat(a + 16 * col + 4 * k) - MemoryUtil.memGetFloat(b + 16 * col + 4 * k));
            c += s;
        }
        return t + c;
    }

    /**
     * The entity's frame job (a worker, ParallelRender.Job.run): the pair interpolated at the job's partial tick into the
     * job's parts, moved from the pair's anchor to the camera. False: the entity is rendered as before.
     */
    static boolean frameParts(Pair p, ParallelRender.Job j) {
        p.await();
        if (!p.usable || p.stat.state != 1) {
            FALLBACKS.incrementAndGet();
            return false;
        }
        if (p.gpuOk) {   // (the render thread copies the GPU records at the merge: mergeGpu)
            j.gpuTick = true;
            return true;
        }
        j.gpuTick = false;
        long t0 = System.nanoTime();
        PartBatch.JobParts out = j.parts;
        out.copyShape(p.jobs[0].parts);
        sample(p, j.pt, out.mem, (float) (p.ax - j.camX), (float) (p.ay - j.camY), (float) (p.az - j.camZ));
        INTERP_NS.addAndGet(System.nanoTime() - t0);
        INTERP_REC.addAndGet(out.n);
        MERGED.incrementAndGet();
        return true;
    }

    private static final ThreadLocal<Interp> INTERP = ThreadLocal.withInitial(Interp::new);

    /** the pair's task: its three keys as mode-8 records; false (the CPU way) for anything but vanilla model parts */
    private static boolean buildGpu(Pair p) {
        PartBatch.JobParts k0 = p.jobs[0].parts, k1 = p.jobs[1].parts, km = p.jobs[2].parts;
        int n = k0.n;
        for (int i = 0; i < n; i++) if (k0.kind[i] != 2) return false;
        if (p.gpuCap < n) {
            if (p.gpu != 0) MemoryUtil.nmemFree(p.gpu);
            p.gpuCap = Math.max(n, 2 * p.gpuCap);
            p.gpu = MemoryUtil.nmemAlloc(288L * p.gpuCap);
        }
        Interp in = INTERP.get();
        for (int i = 0; i < n; i++) {
            long o = p.gpu + 288L * i;
            in.key(k0.mem + 144L * i, o);
            in.key(km.mem + 144L * i, o + 48);
            in.key(k1.mem + 144L * i, o + 96);
            MemoryUtil.memCopy(km.mem + 144L * i + 112, o + 144, 32);   // colour, light and overlay (the middle key's)
        }
        return true;
    }

    /** ParallelRender.drain (render thread): the pair's GPU records into the frame (PartBatch's tick queues) */
    static boolean mergeGpu(Pair p, ParallelRender.Job j) {
        PartBatch.JobParts k0 = p.jobs[0].parts;
        boolean ok = PartBatch.mergeTick(k0.types, k0.first, k0.quads, k0.n, p.gpu, j.buffers,
                (float) (p.ax - j.camX), (float) (p.ay - j.camY), (float) (p.az - j.camZ), j.pt);
        if (ok) {
            GPU_FRAMES.incrementAndGet();
            MERGED.incrementAndGet();
        } else GPU_REFUSED.incrementAndGet();
        return ok;
    }
    static final AtomicLong BIG = new AtomicLong();

    /** the pair's keys at t into out (the records of key 0's shape), translated by (dx, dy, dz) */
    private static void sample(Pair p, float t, long out, float dx, float dy, float dz) {
        PartBatch.JobParts k0 = p.jobs[0].parts, k1 = p.jobs[1].parts, km = p.jobs[2].parts;
        Interp in = INTERP.get();
        int n = k0.n;
        if (KEYS == 3) {
            boolean first = t < 0.5f;
            long a = first ? k0.mem : km.mem, b = first ? km.mem : k1.mem;
            float u = first ? 2f * t : 2f * t - 1f;
            for (int i = 0; i < n; i++) in.interpolate(a + 144L * i, b + 144L * i, out + 144L * i, u, dx, dy, dz);
        } else {
            for (int i = 0; i < n; i++) in.interpolate(k0.mem + 144L * i, k1.mem + 144L * i, out + 144L * i, t, dx, dy, dz);
        }
    }

    private static double tdist(long a, long b) {
        double t = 0;
        for (int k = 0; k < 3; k++) t = Math.max(t, Math.abs(MemoryUtil.memGetFloat(a + 48 + 4 * k) - MemoryUtil.memGetFloat(b + 48 + 4 * k)));
        return t;
    }

    /** per-thread scratch of the interpolation */
    static final class Interp {
        private final Matrix3f A = new Matrix3f(), B = new Matrix3f(), C = new Matrix3f();
        private final Quaternionf QA = new Quaternionf(), QB = new Quaternionf();
        private final float[] SA = new float[3], SB = new float[3];

        private static void read3(long r, Matrix3f m) {
            m.set(MemoryUtil.memGetFloat(r), MemoryUtil.memGetFloat(r + 4), MemoryUtil.memGetFloat(r + 8),
                    MemoryUtil.memGetFloat(r + 16), MemoryUtil.memGetFloat(r + 20), MemoryUtil.memGetFloat(r + 24),
                    MemoryUtil.memGetFloat(r + 32), MemoryUtil.memGetFloat(r + 36), MemoryUtil.memGetFloat(r + 40));
        }

        private static void write3(long r, Matrix3f m) {
            MemoryUtil.memPutFloat(r, m.m00);
            MemoryUtil.memPutFloat(r + 4, m.m01);
            MemoryUtil.memPutFloat(r + 8, m.m02);
            MemoryUtil.memPutFloat(r + 16, m.m10);
            MemoryUtil.memPutFloat(r + 20, m.m11);
            MemoryUtil.memPutFloat(r + 24, m.m12);
            MemoryUtil.memPutFloat(r + 32, m.m20);
            MemoryUtil.memPutFloat(r + 36, m.m21);
            MemoryUtil.memPutFloat(r + 40, m.m22);
        }

        /** a 3x3 as rotation and per-column scale (a mirrored one: the first scale negative) */
        private void decompose(Matrix3f m, Quaternionf q, float[] s) {
            s[0] = (float) Math.sqrt(m.m00 * m.m00 + m.m01 * m.m01 + m.m02 * m.m02);
            s[1] = (float) Math.sqrt(m.m10 * m.m10 + m.m11 * m.m11 + m.m12 * m.m12);
            s[2] = (float) Math.sqrt(m.m20 * m.m20 + m.m21 * m.m21 + m.m22 * m.m22);
            if (m.determinant() < 0) s[0] = -s[0];
            C.set(m);
            if (s[0] != 0) { C.m00 /= s[0]; C.m01 /= s[0]; C.m02 /= s[0]; }
            if (s[1] != 0) { C.m10 /= s[1]; C.m11 /= s[1]; C.m12 /= s[1]; }
            if (s[2] != 0) { C.m20 /= s[2]; C.m21 /= s[2]; C.m22 /= s[2]; }
            C.getNormalizedRotation(q);
        }

        private static boolean same3(long a, long b) {
            for (int col = 0; col < 3; col++)
                for (int k = 0; k < 3; k++)
                    if (MemoryUtil.memGetInt(a + 16 * col + 4 * k) != MemoryUtil.memGetInt(b + 16 * col + 4 * k)) return false;
            return true;
        }

        /** a 3x3 (columns at r, r+16, r+32) of records a and b interpolated into o (equal ones copied as they are) */
        private void lerp3(long a, long b, long o, float t) {
            if (same3(a, b)) {
                for (int col = 0; col < 3; col++) MemoryUtil.memCopy(a + 16 * col, o + 16 * col, 12);
                return;
            }
            read3(a, A);
            read3(b, B);
            decompose(A, QA, SA);
            decompose(B, QB, SB);
            if (QA.dot(QB) < 0) QB.set(-QB.x, -QB.y, -QB.z, -QB.w);
            QA.set(QA.x + (QB.x - QA.x) * t, QA.y + (QB.y - QA.y) * t, QA.z + (QB.z - QA.z) * t, QA.w + (QB.w - QA.w) * t).normalize();
            A.set(QA);
            float s0 = SA[0] + (SB[0] - SA[0]) * t, s1 = SA[1] + (SB[1] - SA[1]) * t, s2 = SA[2] + (SB[2] - SA[2]) * t;
            A.m00 *= s0; A.m01 *= s0; A.m02 *= s0;
            A.m10 *= s1; A.m11 *= s1; A.m12 *= s1;
            A.m20 *= s2; A.m21 *= s2; A.m22 *= s2;
            write3(o, A);
        }

        /** a record's pose as a mode-8 key at o (48 bytes): rotation (quaternion), scale xyz + translation x, translation y z */
        void key(long rec, long o) {
            read3(rec, A);
            decompose(A, QA, SA);
            MemoryUtil.memPutFloat(o, QA.x);
            MemoryUtil.memPutFloat(o + 4, QA.y);
            MemoryUtil.memPutFloat(o + 8, QA.z);
            MemoryUtil.memPutFloat(o + 12, QA.w);
            MemoryUtil.memPutFloat(o + 16, SA[0]);
            MemoryUtil.memPutFloat(o + 20, SA[1]);
            MemoryUtil.memPutFloat(o + 24, SA[2]);
            MemoryUtil.memPutFloat(o + 28, MemoryUtil.memGetFloat(rec + 48));
            MemoryUtil.memPutFloat(o + 32, MemoryUtil.memGetFloat(rec + 52));
            MemoryUtil.memPutFloat(o + 36, MemoryUtil.memGetFloat(rec + 56));
            MemoryUtil.memPutFloat(o + 40, 0f);
            MemoryUtil.memPutFloat(o + 44, 0f);
        }

        /** record a and b (144 bytes each) at t into o: pose, normal matrix, the rest from the nearer one; translation + (dx, dy, dz) */
        void interpolate(long a, long b, long o, float t, float dx, float dy, float dz) {
            MemoryUtil.memCopy(t < 0.5f ? a : b, o, 144);   // colour, light, overlay, coatings (w of the normal rows), m03/m13/m23/m33
            lerp3(a, b, o, t);
            lerp3(a + 64, b + 64, o + 64, t);
            float x0 = MemoryUtil.memGetFloat(a + 48), y0 = MemoryUtil.memGetFloat(a + 52), z0 = MemoryUtil.memGetFloat(a + 56);
            MemoryUtil.memPutFloat(o + 48, x0 + (MemoryUtil.memGetFloat(b + 48) - x0) * t + dx);
            MemoryUtil.memPutFloat(o + 52, y0 + (MemoryUtil.memGetFloat(b + 52) - y0) * t + dy);
            MemoryUtil.memPutFloat(o + 56, z0 + (MemoryUtil.memGetFloat(b + 56) - z0) * t + dz);
        }
    }

    public static String report() {
        StringBuilder q = new StringBuilder(), no = new StringBuilder();
        int obs = 0;
        for (Map.Entry<EntityType<?>, Stat> en : STATS.entrySet()) {
            String n = EntityType.getKey(en.getKey()).getPath();
            if (en.getValue().state == 1) q.append(q.length() > 0 ? " " : "").append(n);
            else if (en.getValue().state == 2) no.append(no.length() > 0 ? " " : "").append(n);
            else obs++;
        }
        long rec = INTERP_REC.get();
        return String.format(java.util.Locale.ROOT, "tick poses: GPU frames %d (refused %d, records %d); captures %d (preparations %d), frames from them %d (%.0f ns a record), fallbacks %d (structure %d, not parts only %d), checks %d (failed %d); qualify [%s], per frame [%s], observing %d; pairs %d",
                GPU_FRAMES.get(), GPU_REFUSED.get(), PartBatch.tickRecords, captures, PREPARED.get(), MERGED.get(), rec == 0 ? 0.0 : (double) INTERP_NS.get() / rec, FALLBACKS.get(), STRUCTURAL.get(), NOT_PARTS.get(), CHECKS.get(), CHECK_FAILS.get(), q, no, obs, PAIRS.size());
    }
}
