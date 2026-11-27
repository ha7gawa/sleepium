package dev.lull.optsupport;

import java.lang.reflect.Constructor;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * lab.opt.WorldgenColumnMemoMixin: the climate values a column's biomes are sampled from, each computed once per column
 * when it does not depend on y.
 *
 * <p>Biomes are sampled per quart (4x4x4 blocks), six climate density functions each. Vanilla's own overworld marks its
 * 2D parts flat_cache, so they are computed per column; a datapack that leaves the markers out has them computed again
 * for every quart up the column - JJThunder To The Max (2 096 blocks tall: 524 quarts a column) computes its
 * continentalness, 160 noise lookups that never read y, 524 times per column: 61 % of all generation CPU in
 * Climate.Sampler.sample (11-09, JFR).
 *
 * <p>Here, once per noise router, each climate function's tree is read by structure: a node depends on y when it is a
 * y gradient, noise read at y (yScale != 0, a shift or sampler at y), an interpolated / per-cell cache or anything not
 * known (a mod's own density function); otherwise it depends on y only through its children. Every largest subtree that
 * does not depend on y and reads noise is marked; in each chunk's sampler (NoiseChunk.wrapNew) the mark becomes a memo
 * of that chunk's 16 columns, keyed by the exact block x and z: the same function of the same arguments, so the same
 * double. Vanilla's 2D caches (flat_cache, cache_2d) are left as they are.
 */
public final class ColumnMemo {
    private ColumnMemo() {}

    public static long marked, routers;

    /** per router (one per level): its six climate functions with the column marks */
    private static final Map<NoiseRouter, DensityFunction[]> ROUTERS = new IdentityHashMap<>();
    /** per router: a copy with the marked climate functions, or the router itself when none were marked */
    private static final Map<NoiseRouter, NoiseRouter> ROUTER_WRAPPED = new IdentityHashMap<>();

    public static synchronized NoiseRouter router(NoiseRouter r) {
        NoiseRouter cached = ROUTER_WRAPPED.get(r);
        if (cached != null) return cached;
        DensityFunction[] f = climate(r);
        boolean same = f[0] == r.temperature() && f[1] == r.vegetation() && f[2] == r.continents()
                && f[3] == r.erosion() && f[4] == r.depth() && f[5] == r.ridges();
        NoiseRouter out = same ? r : new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                f[0], f[1], f[2], f[3], f[4], f[5],
                r.initialDensityWithoutJaggedness(), r.finalDensity(), r.veinToggle(), r.veinRidged(), r.veinGap());
        ROUTER_WRAPPED.put(r, out);
        return out;
    }

    public static synchronized DensityFunction[] climate(NoiseRouter r) {
        DensityFunction[] out = ROUTERS.get(r);
        if (out == null) {
            long before = marked;
            out = new DensityFunction[] {rewrite(r.temperature()), rewrite(r.vegetation()), rewrite(r.continents()), rewrite(r.erosion()),
                    rewrite(r.depth()), rewrite(r.ridges())};
            ROUTERS.put(r, out);
            if (Boolean.getBoolean("lab.colmemo.why"))   // -Dlab.colmemo.why: how each climate function's tree was read
                for (DensityFunction f : new DensityFunction[] {r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges()})
                    System.out.println("[sleep/opt] column memo why: " + why(f, 0));
            routers++;
            System.out.println("[sleep/opt] column memo: " + (marked - before) + " climate subtrees computed once per column");
        }
        return out;
    }

    // ------------------------------------------------------------------ the analysis (by structure, once per router)

    private static String why(DensityFunction df, int depth) {
        StringBuilder sb = new StringBuilder(df.getClass().getName().replace("net.minecraft.world.level.levelgen.", "")).append(df instanceof Record ? "(rec" : "(")
                .append(yFree(df) ? " yfree" : "").append(readsNoise(df) ? " noise" : "").append(")");
        if (depth < 2) {
            sb.append("[");
            for (DensityFunction c : children(df)) sb.append(why(c, depth + 1)).append(", ");
            sb.append("]");
        }
        return sb.toString();
    }

    /** the kinds known not to read y themselves (their children decide) */
    private static boolean pureOfY(String kind) {
        return switch (kind) {
            case "Constant", "Ap2", "MulOrAdd", "Mapped", "Clamp", "RangeChoice", "Spline", "ShiftA", "ShiftB", "BlendAlpha", "BlendOffset",
                 "EndIslandDensityFunction", "HolderHolder" -> true;
            default -> false;
        };
    }

    private static String kind(Object df) {
        String n = df.getClass().getName();
        return n.substring(n.lastIndexOf('$') + 1);
    }

    /** does not read y: decided from the node's kind and its children */
    static boolean yFree(DensityFunction df) {
        if (df instanceof Mark) return true;
        String k = kind(df);
        if (k.equals("Noise")) return nth(df, double.class, 1) instanceof Double d && d == 0.0;   // (xzScale, yScale)
        if (k.equals("ShiftedNoise"))
            return nth(df, double.class, 1) instanceof Double d && d == 0.0 && children(df).stream().allMatch(ColumnMemo::yFree);
        if (k.equals("Marker")) {
            String type = String.valueOf(nthEnum(df));
            if (type.equals("FlatCache") || type.equals("Cache2D")) return true;
            if (type.equals("CacheOnce")) return children(df).stream().allMatch(ColumnMemo::yFree);
            return false;
        }
        if (!pureOfY(k)) return false;
        for (DensityFunction c : children(df)) if (!yFree(c)) return false;
        return true;
    }

    /** reads noise somewhere below (worth a memo) */
    static boolean readsNoise(DensityFunction df) {
        if (df instanceof Mark) return true;
        String k = kind(df);
        if (k.equals("Noise") || k.equals("ShiftedNoise") || k.startsWith("Shift") || k.equals("WeirdScaledSampler") || k.equals("EndIslandDensityFunction")
                || k.equals("BlendedNoise")) return true;
        for (DensityFunction c : children(df)) if (readsNoise(c)) return true;
        return false;
    }

    /** a vanilla 2D cache already (flat_cache / cache_2d): left alone */
    private static boolean cached2D(DensityFunction df) {
        if (!kind(df).equals("Marker")) return false;
        String type = String.valueOf(nthEnum(df));
        return type.equals("FlatCache") || type.equals("Cache2D");
    }

    /** the largest y-free subtrees that read noise, marked; the rest rebuilt around them */
    static DensityFunction rewrite(DensityFunction df) {
        if (kind(df).equals("HolderHolder")) return rewrite(((Holder<DensityFunction>) nth(df, Holder.class, 0)).value());
        if (cached2D(df)) return df;
        if (yFree(df) && readsNoise(df)) {
            marked++;
            return new Mark(df);
        }
        if (!(df instanceof Record) || kind(df).equals("Spline") || kind(df).equals("Noise")) return df;   // a leaf, or a spline read whole
        return rebuild(df);
    }

    /** a record of density functions with each density function component rewritten (canonical constructor) */
    private static DensityFunction rebuild(DensityFunction df) {
        try {
            java.lang.reflect.Field[] rc = fields(df.getClass());
            Object[] args = new Object[rc.length];
            Class<?>[] types = new Class<?>[rc.length];
            boolean changed = false;
            for (int i = 0; i < rc.length; i++) {
                Object v = rc[i].get(df);
                types[i] = rc[i].getType();
                if (v instanceof DensityFunction d && DensityFunction.class.isAssignableFrom(types[i])) {
                    DensityFunction w = rewrite(d);
                    changed |= w != d;
                    v = w;
                }
                args[i] = v;
            }
            if (!changed) return df;
            Constructor<?> c = df.getClass().getDeclaredConstructor(types);
            c.setAccessible(true);
            return (DensityFunction) c.newInstance(args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return df;   // not rebuilt: computed as before
        }
    }

    /**
     * A record's n-th component of the given type (null when there is none). By type and position, not by name: 1.20.1's
     * production records carry SRG component names.
     */
    private static Object nth(Object o, Class<?> type, int n) {
        if (!(o instanceof Record)) return null;
        try {
            for (java.lang.reflect.Field c : fields(o.getClass())) {
                if (!type.isAssignableFrom(c.getType()) || n-- > 0) continue;
                return c.get(o);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    /** a record's first enum component (a Marker's type) */
    private static Object nthEnum(Object o) {
        if (!(o instanceof Record)) return null;
        try {
            for (java.lang.reflect.Field c : fields(o.getClass())) {
                if (!c.getType().isEnum()) continue;
                return c.get(o);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    /**
     * A record's component fields in component order (its non-static fields), accessible. Fields, not accessors: 1.20.1's
     * production renames a record's accessor methods apart from its component names (RecordComponent.getAccessor finds
     * nothing there).
     */
    private static java.lang.reflect.Field[] fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            java.util.ArrayList<java.lang.reflect.Field> out = new java.util.ArrayList<>();
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                out.add(f);
            }
            return out.toArray(new java.lang.reflect.Field[0]);
        });
    }

    private static final Map<Class<?>, java.lang.reflect.Field[]> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** the density functions a node reads: its record components, a spline's coordinates, a holder's value */
    private static List<DensityFunction> children(DensityFunction df) {
        java.util.ArrayList<DensityFunction> out = new java.util.ArrayList<>();
        if (kind(df).equals("HolderHolder")) {
            out.add(((Holder<DensityFunction>) nth(df, Holder.class, 0)).value());
            return out;
        }
        if (kind(df).equals("Spline")) {
            splineCoordinates(nth(df, Object.class, 0), out);   // its only component
            return out;
        }
        if (!(df instanceof Record)) return out;
        try {
            for (java.lang.reflect.Field c : fields(df.getClass())) if (c.get(df) instanceof DensityFunction d) out.add(d);
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.add(new Unknown());   // unreadable: counts as reading y
        }
        return out;
    }

    /** CubicSpline: a Multipoint's coordinate function and its values' splines; a Constant has none */
    private static void splineCoordinates(Object spline, List<DensityFunction> out) {
        if (!(spline instanceof Record)) return;
        if (!kind(spline).equals("Multipoint")) return;   // CubicSpline.Constant: no coordinate
        Object coord = nth(spline, Object.class, 0);   // Multipoint(coordinate, locations, values, derivatives, min, max)
        Object f = coord == null ? null : nth(coord, Holder.class, 0);   // Spline.Coordinate(function)
        if (f instanceof Holder<?> h && h.value() instanceof DensityFunction d) out.add(d);
        else out.add(new Unknown());
        if (nth(spline, List.class, 0) instanceof List<?> values) for (Object v : values) splineCoordinates(v, out);
    }

    /** stands for something the analysis cannot read: never y-free */
    private static final class Unknown implements DensityFunction {
        public double compute(FunctionContext c) {
            throw new UnsupportedOperationException();
        }

        public void fillArray(double[] a, ContextProvider p) {
            throw new UnsupportedOperationException();
        }

        public DensityFunction mapAll(Visitor v) {
            return this;
        }

        public double minValue() {
            return 0;
        }

        public double maxValue() {
            return 0;
        }

        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------------------------------------------------------ the mark (router) and the memo (per chunk)

    /** a y-free subtree in the router; NoiseChunk.wrapNew turns it into a Memo for its chunk (WorldgenColumnMemoMixin) */
    public static final class Mark implements DensityFunction {
        public final DensityFunction wrapped;

        Mark(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        public double compute(FunctionContext c) {
            return wrapped.compute(c);
        }

        public void fillArray(double[] a, ContextProvider p) {
            wrapped.fillArray(a, p);
        }

        public DensityFunction mapAll(Visitor v) {
            return v.apply(new Mark(wrapped.mapAll(v)));
        }

        public double minValue() {
            return wrapped.minValue();
        }

        public double maxValue() {
            return wrapped.maxValue();
        }

        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return wrapped.codec();
        }
    }

    public static long hits, misses;

    /** a chunk's memo of a y-free subtree: one value per column (exact block x, z), 16 columns a chunk */
    public static final class Memo implements DensityFunction {
        private final DensityFunction wrapped;
        private final Entry[] slots = new Entry[16];

        public Memo(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        private record Entry(int x, int z, double v) {}

        public double compute(FunctionContext c) {
            int x = c.blockX(), z = c.blockZ();
            int i = ((x >> 2) & 3) | (((z >> 2) & 3) << 2);
            Entry e = slots[i];
            if (e != null && e.x == x && e.z == z) return e.v;
            double v = wrapped.compute(c);
            slots[i] = new Entry(x, z, v);
            return v;
        }

        public void fillArray(double[] a, ContextProvider p) {
            p.fillAllDirectly(a, this);
        }

        public DensityFunction mapAll(Visitor v) {
            return v.apply(new Memo(wrapped.mapAll(v)));
        }

        public double minValue() {
            return wrapped.minValue();
        }

        public double maxValue() {
            return wrapped.maxValue();
        }

        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return wrapped.codec();
        }
    }
}
