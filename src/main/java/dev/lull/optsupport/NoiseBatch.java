package dev.lull.optsupport;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Batched octaves (-Dlab.dfc.batch, with DfcNoiseChunkMixin): a NormalNoise / BlendedNoise evaluated for a run of
 * points of one corner column (x and z fixed, y stepping) octave by octave instead of point by point. Per point every
 * value is vanilla's: the same IEEE operations in the same order (no FMA, nothing reassociated), octaves summed in
 * vanilla's order with vanilla's running factors.
 *
 * <p>Per octave: the x / z part (wrap, offset, floor, fraction, smoothstep, the two x permutation lookups) once for the
 * batch - the same doubles / ints every point would compute. Then per point the y part. Points whose y lattice cell is
 * the same form a run (low-frequency octaves: the whole column can be one cell); for a run of at least {@link #RUNMIN}
 * points the eight gradients are looked up once, their x and z products ((double) g * x, exact) kept, and the points
 * go through a branch-free loop with only y-dependent arithmetic - (g.x * x + g.y * y) + g.z * z, smoothstep and
 * lerp3 in vanilla's order - which HotSpot's superword pass vectorizes (AVX / AVX2: 4 doubles per instruction; the
 * kernel 3.09 -> 1.28 ns per point with superword on, UseAVX=1 the same). Shorter runs take vanilla's per-point
 * sampleAndLerp with the hoisted x / z part; an octave with on average fewer than RUNMIN points per y cell
 * (high-frequency) goes point by point in one pass (no run bookkeeping).
 *
 * <p>Lazy and exact for conditional nodes: the compiled corner code asks for a value at a point (at(x, y, z)); the first
 * ask in a column fills a block from that point up - at most BLOCK points and, when the previous column's asks stopped
 * lower (a y cut-off of the tree: the slide's zero factor, a range choice on y), no further than that height. An ask
 * outside the held block fills a new one. Values are pure functions of the point, so extra points are only extra work.
 *
 * <p>Instances are per NoiseChunk node (one thread) and keep only their values; temporaries are one Scratch per thread;
 * octave tables (Spec) are read once per noise object and shared. -Dlab.dfc.batch.ab times batched against unbatched
 * corner columns in one JVM (checkerboard of chunks).
 */
public final class NoiseBatch {
    private NoiseBatch() {}

    public static final boolean ON = Boolean.getBoolean("lab.dfc.batch");
    /** NormalNoise: points per lazily filled block of a column (from the first point asked for up); BlendedNoise: the whole rest */
    public static final int BLOCK = Integer.getInteger("lab.dfc.batch.block", 64);
    /** -Dlab.dfc.batch.pred=false: blocks not cut at the previous column's highest ask */
    public static final boolean PRED = !"false".equals(System.getProperty("lab.dfc.batch.pred"));
    public static final int RUNMIN = Integer.getInteger("lab.dfc.batch.runmin", 4);
    /** -Dlab.dfc.batch.blended=false / normal=false: one kind off */
    public static final boolean BLENDED = !"false".equals(System.getProperty("lab.dfc.batch.blended"));
    public static final boolean NORMAL = !"false".equals(System.getProperty("lab.dfc.batch.normal"));
    static final int MAX = 64;
    /** -Dlab.dfc.batch.stats: points asked / computed, octave points by run kernel / per point */
    static final boolean STATS = Boolean.getBoolean("lab.dfc.batch.stats");
    static final java.util.concurrent.atomic.AtomicLongArray ST = new java.util.concurrent.atomic.AtomicLongArray(6);

    /** -Dlab.dfc.batch.ab: every other chunk (checkerboard) batched, its corner columns timed per mode */
    public static final boolean AB = Boolean.getBoolean("lab.dfc.batch.ab");
    private static final java.util.concurrent.atomic.AtomicLongArray AT = new java.util.concurrent.atomic.AtomicLongArray(4);

    public static void timed(int mode, long ns) {
        AT.addAndGet(2 * mode, ns);
        long n = AT.incrementAndGet(2 * mode + 1);
        if (n >= 4096 && AT.get(3 - 2 * mode) >= 4096) {
            // a window: both modes' sums since the last report, then reset
            long t0 = AT.getAndSet(0, 0), n0 = AT.getAndSet(1, 0), t1 = AT.getAndSet(2, 0), n1 = AT.getAndSet(3, 0);
            if (n0 == 0 || n1 == 0) return;
            double a = t0 / (double) n0, b = t1 / (double) n1;
            System.out.printf("[sleep/opt] noise batch a/b: vanilla octaves %.0f ns per corner column (%d), batched %.0f (%d) = %.1f %%%n", a, n0, b, n1,
                    100.0 * b / a);
        }
    }

    static void stat(int i, long v) {
        long n = ST.addAndGet(i, v);
        if (i == 0 && (n & ((1 << 22) - 1)) == 0)
            System.out.println("[sleep/opt] noise batch: normal asked " + ST.get(0) + " computed " + ST.get(1) + "; blended asked " + ST.get(2) + " computed "
                    + ST.get(3) + "; octave points by runs " + ST.get(4) + ", per point " + ST.get(5));
    }

    private static final Map<Object, Object> SPECS = new WeakHashMap<>();
    private static final double[] G = NoiseMath.GRAD;

    private static Object get(Object o, String f) {
        return Reflect.get(o, f);
    }

    /** one octave: its permutation table and offsets */
    static final class Oct {
        final byte[] p;
        final double xo, yo, zo;

        Oct(ImprovedNoise n) {
            p = (byte[]) get(n, "p");
            xo = n.xo;
            yo = n.yo;
            zo = n.zo;
        }
    }

    /** per-batch scratch of the octave loop (one per NoiseChunk node) */
    static final class Scratch {
        final double[] wdy = new double[MAX], dy = new double[MAX], vals = new double[MAX];
        final int[] ja = new int[MAX];
        // the batch nodes' own temporaries (Normal: ys .. t2; Blended: c1 .. lmax), shared by the thread's nodes
        final double[] ys = new double[MAX], y2 = new double[MAX], t1 = new double[MAX], t2 = new double[MAX];
        final double[] c1 = new double[MAX], c4 = new double[MAX], d10 = new double[MAX], d16 = new double[MAX], d8 = new double[MAX], d9 = new double[MAX],
                ysub = new double[MAX];
        final int[] lmin = new int[MAX], lmax = new int[MAX];

        /**
         * vals[q] = o.noise(xIn, wrap(ys[q] * f), zIn, yScale, ys[q] * f) for q < n - vanilla's ImprovedNoise.noise with
         * the x / z part computed once.
         */
        void octave(Oct o, double xIn, double zIn, double[] ys, double f, double yScale, int n) {
            double d0 = xIn + o.xo;
            double d2 = zIn + o.zo;
            int gx = Mth.floor(d0);
            int gz = Mth.floor(d2);
            double fx = d0 - (double) gx;
            double fz = d2 - (double) gz;
            byte[] p = o.p;
            int pi = p[gx & 0xFF] & 0xFF;
            int pj = p[gx + 1 & 0xFF] & 0xFF;
            double x1 = fx - 1.0, z1 = fz - 1.0;
            double sx = Mth.smoothstep(fx), sz = Mth.smoothstep(fz);
            double yo = o.yo;
            double[] wdy = this.wdy, dy = this.dy, vals = this.vals;
            if (n < RUNMIN || Math.abs(ys[n - 1] - ys[0]) * f * RUNMIN > (double) (n - 1)) {
                // a high-frequency octave (on average less than RUNMIN points per y cell): point by point, one pass
                if (STATS) stat(5, n);
                for (int q = 0; q < n; q++) {
                    double yMax = ys[q] * f;
                    double d1 = PerlinNoise.wrap(yMax) + yo;
                    int j = Mth.floor(d1);
                    double d4 = d1 - (double) j;
                    double d6;
                    if (yScale != 0.0) {
                        double d7;
                        if (yMax >= 0.0 && yMax < d4) {
                            d7 = yMax;
                        } else {
                            d7 = d4;
                        }
                        d6 = (double) Mth.floor(d7 / yScale + 1.0E-7F) * yScale;
                    } else {
                        d6 = 0.0;
                    }
                    double w = d4 - d6;
                    double w1 = w - 1.0;
                    int ka = (p[pi + j & 0xFF] & 0xFF) + gz;
                    int kb = (p[pi + j + 1 & 0xFF] & 0xFF) + gz;
                    int kc = (p[pj + j & 0xFF] & 0xFF) + gz;
                    int kd = (p[pj + j + 1 & 0xFF] & 0xFF) + gz;
                    vals[q] = Mth.lerp3(sx, Mth.smoothstep(d4), sz,
                            NoiseMath.dot(p[ka & 0xFF], fx, w, fz), NoiseMath.dot(p[kc & 0xFF], x1, w, fz),
                            NoiseMath.dot(p[kb & 0xFF], fx, w1, fz), NoiseMath.dot(p[kd & 0xFF], x1, w1, fz),
                            NoiseMath.dot(p[ka + 1 & 0xFF], fx, w, z1), NoiseMath.dot(p[kc + 1 & 0xFF], x1, w, z1),
                            NoiseMath.dot(p[kb + 1 & 0xFF], fx, w1, z1), NoiseMath.dot(p[kd + 1 & 0xFF], x1, w1, z1));
                }
                return;
            }
            int[] ja = this.ja;
            for (int q = 0; q < n; q++) {
                double yMax = ys[q] * f;
                double d1 = PerlinNoise.wrap(yMax) + yo;
                int j = Mth.floor(d1);
                double d4 = d1 - (double) j;
                double d6;
                if (yScale != 0.0) {
                    double d7;
                    if (yMax >= 0.0 && yMax < d4) {
                        d7 = yMax;
                    } else {
                        d7 = d4;
                    }
                    d6 = (double) Mth.floor(d7 / yScale + 1.0E-7F) * yScale;
                } else {
                    d6 = 0.0;
                }
                ja[q] = j;
                dy[q] = d4;
                wdy[q] = d4 - d6;
            }
            double[] g = G;
            int q = 0;
            while (q < n) {
                int j = ja[q];
                int e = q + 1;
                while (e < n && ja[e] == j) e++;
                int ka = (p[pi + j & 0xFF] & 0xFF) + gz;
                int kb = (p[pi + j + 1 & 0xFF] & 0xFF) + gz;
                int kc = (p[pj + j & 0xFF] & 0xFF) + gz;
                int kd = (p[pj + j + 1 & 0xFF] & 0xFF) + gz;
                if (STATS) stat(e - q >= RUNMIN ? 4 : 5, e - q);
                if (e - q >= RUNMIN) {
                    int h0 = (p[ka & 0xFF] & 15) << 2, h1 = (p[kc & 0xFF] & 15) << 2, h2 = (p[kb & 0xFF] & 15) << 2, h3 = (p[kd & 0xFF] & 15) << 2;
                    int h4 = (p[ka + 1 & 0xFF] & 15) << 2, h5 = (p[kc + 1 & 0xFF] & 15) << 2, h6 = (p[kb + 1 & 0xFF] & 15) << 2;
                    int h7 = (p[kd + 1 & 0xFF] & 15) << 2;
                    run(wdy, dy, vals, q, e, sx, sz,
                            g[h0] * fx, g[h0 + 1], g[h0 + 2] * fz, g[h1] * x1, g[h1 + 1], g[h1 + 2] * fz,
                            g[h2] * fx, g[h2 + 1], g[h2 + 2] * fz, g[h3] * x1, g[h3 + 1], g[h3 + 2] * fz,
                            g[h4] * fx, g[h4 + 1], g[h4 + 2] * z1, g[h5] * x1, g[h5 + 1], g[h5 + 2] * z1,
                            g[h6] * fx, g[h6 + 1], g[h6 + 2] * z1, g[h7] * x1, g[h7 + 1], g[h7 + 2] * z1);
                } else {
                    for (int r = q; r < e; r++) {
                        double w = wdy[r];
                        double w1 = w - 1.0;
                        vals[r] = Mth.lerp3(sx, Mth.smoothstep(dy[r]), sz,
                                NoiseMath.dot(p[ka & 0xFF], fx, w, fz), NoiseMath.dot(p[kc & 0xFF], x1, w, fz),
                                NoiseMath.dot(p[kb & 0xFF], fx, w1, fz), NoiseMath.dot(p[kd & 0xFF], x1, w1, fz),
                                NoiseMath.dot(p[ka + 1 & 0xFF], fx, w, z1), NoiseMath.dot(p[kc + 1 & 0xFF], x1, w, z1),
                                NoiseMath.dot(p[kb + 1 & 0xFF], fx, w1, z1), NoiseMath.dot(p[kd + 1 & 0xFF], x1, w1, z1));
                    }
                }
                q = e;
            }
        }

        /**
         * One lattice cell, points q..e-1: vanilla's sampleAndLerp with the gradient products of x (a*) and z (c*) and
         * the y components (g*) fixed - per corner (g.x * x + g.y * y) + g.z * z as vanilla adds it. Straight-line and
         * branch-free: vectorized by C2.
         */
        private static void run(double[] wdy, double[] dy, double[] vals, int q, int e, double sx, double sz,
                                double a0, double g0, double c0, double a1, double g1, double c1, double a2, double g2, double c2,
                                double a3, double g3, double c3, double a4, double g4, double c4, double a5, double g5, double c5,
                                double a6, double g6, double c6, double a7, double g7, double c7) {
            for (int r = q; r < e; r++) {
                double y0 = wdy[r];
                double y1 = y0 - 1.0;
                double t = dy[r];
                double sy = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
                double e0 = a0 + g0 * y0 + c0;
                double e1 = a1 + g1 * y0 + c1;
                double e2 = a2 + g2 * y1 + c2;
                double e3 = a3 + g3 * y1 + c3;
                double e4 = a4 + g4 * y0 + c4;
                double e5 = a5 + g5 * y0 + c5;
                double e6 = a6 + g6 * y1 + c6;
                double e7 = a7 + g7 * y1 + c7;
                // Mth.lerp3(sx, sy, sz, ...) = lerp(sz, lerp2(sx, sy, e0..e3), lerp2(sx, sy, e4..e7)); lerp(d, a, b) = a + d * (b - a)
                double l01 = e0 + sx * (e1 - e0);
                double l23 = e2 + sx * (e3 - e2);
                double l45 = e4 + sx * (e5 - e4);
                double l67 = e6 + sx * (e7 - e6);
                double m0 = l01 + sy * (l23 - l01);
                double m1 = l45 + sy * (l67 - l45);
                vals[r] = m0 + sz * (m1 - m0);
            }
        }
    }

    /** the block of a corner column held: points y0, y0 + step, ... below yHi of column (x, z) */
    static final class Col {
        final int step, yTop, shift, block;
        int cx, cz, y0, yHi;
        /** the highest y asked in this column; the previous column's (the prediction where the asks stop) */
        int asked, pred = Integer.MAX_VALUE;
        boolean valid;

        Col(int step, int yTop, int block) {
            this.block = block;
            this.step = step;
            this.yTop = yTop;
            this.shift = step > 0 && Integer.bitCount(step) == 1 ? Integer.numberOfTrailingZeros(step) : -1;
        }

        /** the index of block y in the held block, or -1 */
        int index(int x, int y, int z) {
            if (!valid || x != cx || z != cz) return -1;
            if (y > asked) asked = y;
            if (y < y0 || y >= yHi) return -1;
            int d = y - y0;
            if (shift >= 0) return (d & (step - 1)) == 0 ? d >> shift : -1;
            return d % step == 0 ? d / step : -1;
        }

        /** a new block from y up: its point count */
        int start(int x, int y, int z) {
            if (!valid || x != cx || z != cz) {
                pred = valid ? asked : Integer.MAX_VALUE;
                asked = y;
            } else if (y > asked) {
                asked = y;
            }
            int n = y > yTop ? 1 : Math.min(block, (yTop - y) / step + 1);
            // the asks of a column's nodes stop where the previous column's did (a y cut-off of the tree, e.g. the slide
            // or a range choice on y): fill no further than that; an ask above it starts a new block
            if (PRED && pred >= y) n = Math.min(n, (pred - y) / step + 1);
            n = Math.max(1, Math.min(n, MAX));
            cx = x;
            cz = z;
            y0 = y;
            yHi = y + (n - 1) * step + 1;
            valid = true;
            return n;
        }
    }

    /** one scratch per thread: a node keeps only its values (out), so a NoiseChunk's nodes stay small in the cache */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    // ---------------------------------------------------------------- NormalNoise

    /** one PerlinNoise's non-null octaves in vanilla's order, with the running input / value factors */
    static final class PerlinSpec {
        final Oct[] o;
        final double[] inF, valF, amp;

        PerlinSpec(PerlinNoise pn) {
            ImprovedNoise[] levels = (ImprovedNoise[]) get(pn, "noiseLevels");
            DoubleList amps = (DoubleList) get(pn, "amplitudes");
            double d1 = (Double) get(pn, "lowestFreqInputFactor");
            double d2 = (Double) get(pn, "lowestFreqValueFactor");
            int c = 0;
            for (ImprovedNoise l : levels) if (l != null) c++;
            o = new Oct[c];
            inF = new double[c];
            valF = new double[c];
            amp = new double[c];
            int k = 0;
            for (int i = 0; i < levels.length; i++) {
                if (levels[i] != null) {
                    o[k] = new Oct(levels[i]);
                    inF[k] = d1;
                    valF[k] = d2;
                    amp[k] = amps.getDouble(i);
                    k++;
                }
                d1 *= 2.0;
                d2 /= 2.0;
            }
        }

        /** acc[q] = PerlinNoise.getValue(x, ys[q], z) for q < n */
        void fill(Scratch s, double x, double[] ys, double z, int n, double[] acc) {
            for (int q = 0; q < n; q++) acc[q] = 0.0;
            double[] vals = s.vals;
            for (int k = 0; k < o.length; k++) {
                double f = inF[k];
                s.octave(o[k], PerlinNoise.wrap(x * f), PerlinNoise.wrap(z * f), ys, f, 0.0 * f, n);
                double a = amp[k], v = valF[k];
                for (int q = 0; q < n; q++) acc[q] += a * vals[q] * v;
            }
        }
    }

    static final class NormalSpec {
        final PerlinSpec first, second;
        final double valueFactor;

        NormalSpec(NormalNoise nn) {
            first = new PerlinSpec((PerlinNoise) get(nn, "first"));
            second = new PerlinSpec((PerlinNoise) get(nn, "second"));
            valueFactor = (Double) get(nn, "valueFactor");
        }
    }

    private static Object spec(Object noise, java.util.function.Function<Object, Object> make) {
        synchronized (SPECS) {
            return SPECS.computeIfAbsent(noise, make);
        }
    }

    /**
     * A DensityFunctions.Noise node of a NoiseChunk's corner tree: holder.getValue(x * xzScale, y * yScale, z * xzScale)
     * at the column's points, filled lazily block by block (BLOCK points from the first point asked for).
     */
    public static final class Normal {
        private final NormalSpec s;
        private final double xzScale, yScale;
        private final Col col;
        private final double[] out = new double[MAX];

        /** step: block y between a column's points (cell height); yTop: the top point's block y */
        public Normal(NormalNoise nn, double xzScale, double yScale, int step, int yTop) {
            this.s = nn == null ? null : (NormalSpec) spec(nn, k -> new NormalSpec((NormalNoise) k));
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.col = new Col(step, yTop, BLOCK);
        }

        public static boolean usable(Object holderNoise, int count) {
            return ON && NORMAL && holderNoise instanceof NormalNoise && count <= MAX;
        }

        /** holder.getValue(x * xzScale, y * yScale, z * xzScale) at block (x, y, z) of a corner column */
        public double at(int x, int y, int z) {
            if (STATS) stat(0, 1);
            int q = col.index(x, y, z);
            if (q < 0) {
                if (s == null) return 0.0;
                q = fill(x, y, z);
            }
            return out[q];
        }

        private int fill(int x, int y, int z) {
            int n = col.start(x, y, z);
            if (STATS) stat(1, n);
            int step = col.step;
            Scratch sc = SCRATCH.get();
            double[] ys = sc.ys, y2 = sc.y2, t1 = sc.t1, t2 = sc.t2;
            for (int q = 0; q < n; q++) {
                double yy = (double) (y + q * step) * yScale;
                ys[q] = yy;
                y2[q] = yy * 1.0181268882175227;
            }
            double dx = (double) x * xzScale, dz = (double) z * xzScale;
            NormalSpec s = this.s;
            s.first.fill(sc, dx, ys, dz, n, t1);
            s.second.fill(sc, dx * 1.0181268882175227, y2, dz * 1.0181268882175227, n, t2);
            double vf = s.valueFactor;
            for (int q = 0; q < n; q++) out[q] = (t1[q] + t2[q]) * vf;
            return 0;
        }
    }

    // ---------------------------------------------------------------- BlendedNoise

    static final class BlendedSpec {
        final Oct[] main = new Oct[8], min = new Oct[16], max = new Oct[16];
        final double xzMultiplier, yMultiplier, xzFactor, yFactor, smearScaleMultiplier;

        BlendedSpec(BlendedNoise bn) {
            PerlinNoise mn = (PerlinNoise) get(bn, "mainNoise"), lo = (PerlinNoise) get(bn, "minLimitNoise"), hi = (PerlinNoise) get(bn, "maxLimitNoise");
            for (int i = 0; i < 8; i++) if (mn.getOctaveNoise(i) != null) main[i] = new Oct(mn.getOctaveNoise(i));
            for (int i = 0; i < 16; i++) {
                if (lo.getOctaveNoise(i) != null) min[i] = new Oct(lo.getOctaveNoise(i));
                if (hi.getOctaveNoise(i) != null) max[i] = new Oct(hi.getOctaveNoise(i));
            }
            xzMultiplier = (Double) get(bn, "xzMultiplier");
            yMultiplier = (Double) get(bn, "yMultiplier");
            xzFactor = (Double) get(bn, "xzFactor");
            yFactor = (Double) get(bn, "yFactor");
            smearScaleMultiplier = (Double) get(bn, "smearScaleMultiplier");
        }
    }

    /** BlendedNoise.compute at the block positions of a corner column, filled lazily block by block */
    public static final class Blended {
        private final BlendedSpec s;
        private final Col col;
        private final double[] out = new double[MAX];
        public Blended(BlendedNoise bn, int step, int yTop) {
            this.s = (BlendedSpec) spec(bn, k -> new BlendedSpec((BlendedNoise) k));
            this.col = new Col(step, yTop, MAX);
        }

        public static boolean usable(Object o, int count) {
            return ON && BLENDED && o != null && o.getClass() == BlendedNoise.class && count <= MAX;
        }

        /** BlendedNoise.compute at block (x, y, z) of a corner column */
        public double at(int x, int y, int z) {
            if (STATS) stat(2, 1);
            int q = col.index(x, y, z);
            if (q < 0) q = fill(x, y, z);
            return out[q];
        }

        private int fill(int bx, int by, int bz) {
            int n = col.start(bx, by, bz);
            if (STATS) stat(3, n);
            int step = col.step;
            BlendedSpec s = this.s;
            Scratch sc = SCRATCH.get();
            double[] c1 = sc.c1, c4 = sc.c4, d10 = sc.d10, d16 = sc.d16, d8 = sc.d8, d9 = sc.d9, vals = sc.vals;
            int[] lmin = sc.lmin, lmax = sc.lmax;
            double d0 = (double) bx * s.xzMultiplier;
            double d2 = (double) bz * s.xzMultiplier;
            double d3 = d0 / s.xzFactor;
            double d5 = d2 / s.xzFactor;
            double d6 = s.yMultiplier * s.smearScaleMultiplier;
            double d7 = d6 / s.yFactor;
            for (int q = 0; q < n; q++) {
                double d1 = (double) (by + q * step) * s.yMultiplier;
                c1[q] = d1;
                c4[q] = d1 / s.yFactor;
                d10[q] = 0.0;
                d8[q] = 0.0;
                d9[q] = 0.0;
            }
            double d11 = 1.0;
            for (int i = 0; i < 8; i++) {
                Oct o = s.main[i];
                if (o != null) {
                    sc.octave(o, PerlinNoise.wrap(d3 * d11), PerlinNoise.wrap(d5 * d11), c4, d11, d7 * d11, n);
                    for (int q = 0; q < n; q++) d10[q] += vals[q] / d11;
                }
                d11 /= 2.0;
            }
            int nmin = 0, nmax = 0;
            for (int q = 0; q < n; q++) {
                double v = (d10[q] / 10.0 + 1.0) / 2.0;
                d16[q] = v;
                if (!(v >= 1.0)) lmin[nmin++] = q;
                if (!(v <= 0.0)) lmax[nmax++] = q;
            }
            double[] ysub = sc.ysub;
            d11 = 1.0;
            for (int j = 0; j < 16; j++) {
                double d12 = PerlinNoise.wrap(d0 * d11);
                double d14 = PerlinNoise.wrap(d2 * d11);
                double d15 = d6 * d11;
                Oct lo = s.min[j], hi = s.max[j];
                if (lo != null && nmin > 0) {
                    if (nmin == n) {
                        sc.octave(lo, d12, d14, c1, d11, d15, n);
                        for (int q = 0; q < n; q++) d8[q] += vals[q] / d11;
                    } else {
                        for (int q = 0; q < nmin; q++) ysub[q] = c1[lmin[q]];
                        sc.octave(lo, d12, d14, ysub, d11, d15, nmin);
                        for (int q = 0; q < nmin; q++) d8[lmin[q]] += vals[q] / d11;
                    }
                }
                if (hi != null && nmax > 0) {
                    if (nmax == n) {
                        sc.octave(hi, d12, d14, c1, d11, d15, n);
                        for (int q = 0; q < n; q++) d9[q] += vals[q] / d11;
                    } else {
                        for (int q = 0; q < nmax; q++) ysub[q] = c1[lmax[q]];
                        sc.octave(hi, d12, d14, ysub, d11, d15, nmax);
                        for (int q = 0; q < nmax; q++) d9[lmax[q]] += vals[q] / d11;
                    }
                }
                d11 /= 2.0;
            }
            for (int q = 0; q < n; q++) out[q] = Mth.clampedLerp(d8[q] / 512.0, d9[q] / 512.0, d16[q]) / 128.0;
            return 0;
        }
    }
}
