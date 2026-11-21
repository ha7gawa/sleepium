package dev.lull.optsupport;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * lab.opt.WorldgenNoiseOnceMixin: noise inside the interpolated density trees computed once per corner and once per
 * column where vanilla computes the same value again.
 *
 * <p>The overworld's interpolated tree reads {@code sloped_cheese} (the 3D base noise, 40 Perlin octaves at a corner,
 * plus the jagged noise in the mountains) as the input of a range choice and then again inside whichever branch the
 * choice takes: every corner computes it twice. Vanilla marks the other shared subtree there (entrances) cache_once;
 * here every subtree of an interpolated tree that occurs more than once and reads noise gets the same mark. The jagged
 * noise is read with a y scale of 0: its value is the same for the whole column (y * 0.0 is +0.0 or -0.0, and either
 * gives the same octave coordinate yo + 0.0), yet vanilla computes it at all 49 corners of the column. Every largest
 * subtree of an interpolated tree that does not read y and reads noise not already in a 2D cache gets a cache_2d
 * mark. NoiseChunk.wrapNew turns the marks into its own CacheOnce and Cache2D, as it does for vanilla's marks.
 *
 * <p>Exact: a mark only wraps subtrees made of vanilla nodes whose values are pure functions of the position (and of
 * the chunk's blender), where every Cache2D already there wraps a function that ignores y. CacheOnce hands out a value
 * computed at the same point (the same interpolation counter) or the value its fillArray computed for the same index
 * of the same column, and every node allowed here fills an array with exactly the values it computes point by point.
 * Cache2D hands out the value computed at the same block x and z, the same double for a function that ignores y.
 * Nothing changes in which noises are sampled at which coordinates, only how often.
 *
 * <p>Once per router (a few per level), kept in a small ring by weak key.
 */
public final class NoiseOnce {
    private NoiseOnce() {}

    public static long once, column, routers;

    private static final int RING = 8;
    private static final WeakReference<?>[] KEYS = new WeakReference<?>[RING];
    private static final NoiseRouter[] VALUES = new NoiseRouter[RING];
    private static int next;
    private static final boolean WHY = Boolean.getBoolean("lab.noiseonce.why");

    /** the router with the marks in its final density (the same router when nothing is marked or on any failure) */
    public static synchronized NoiseRouter router(NoiseRouter r) {
        for (int i = 0; i < RING; i++) if (KEYS[i] != null && KEYS[i].get() == r) return VALUES[i];
        NoiseRouter out = r;
        try {
            long o = once, c = column;
            Rewriter w = new Rewriter();
            DensityFunction f = w.top(r.finalDensity());
            if (f != r.finalDensity())
                out = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(),
                        r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(), r.initialDensityWithoutJaggedness(), f, r.veinToggle(),
                        r.veinRidged(), r.veinGap());
            routers++;
            System.out.println("[sleep/opt] noise once: " + (once - o) + " repeated subtrees computed once per corner, " + (column - c)
                    + " subtrees free of y once per column (" + w.trees + " interpolated trees, " + w.count.size() + " distinct subtrees, "
                    + w.count.values().stream().filter(n -> n > 1).count() + " repeated)");
            if (WHY) {   // -Dlab.noiseonce.why: the nodes walked outside interpolation, how each repeated subtree was read
                System.out.println("[sleep/opt] noise once outside: " + w.trace);
                for (Map.Entry<DensityFunction, Integer> e : w.count.entrySet())
                    if (e.getValue() > 1)
                        System.out.println("[sleep/opt] noise once why: " + kind(e.getKey()) + " x" + e.getValue() + " pure " + pure(e.getKey()) + " (" + impure(e.getKey())
                                + ") live " + readsLiveNoise(e.getKey()) + " yfree " + ColumnMemo.yFree(e.getKey()));
            }
        } catch (RuntimeException e) {
            System.out.println("[sleep/opt] noise once: router left as it is (" + e + ")");
            out = r;
        }
        KEYS[next] = new WeakReference<>(r);
        VALUES[next] = out;
        next = (next + 1) % RING;
        return out;
    }

    // ------------------------------------------------------------------ the analysis (by structure)

    private static String kind(Object o) {
        String n = o.getClass().getName();
        return n.substring(Math.max(n.lastIndexOf('$'), n.lastIndexOf('.')) + 1);
    }

    private static String markerType(Object marker) {
        return String.valueOf(Reflect.get(marker, "type"));
    }

    /** vanilla nodes whose value is a pure function of the position (given their children) */
    private static boolean pureKind(String k) {
        return switch (k) {
            case "Constant", "Ap2", "MulOrAdd", "Mapped", "Clamp", "RangeChoice", "Spline", "Noise", "ShiftedNoise", "ShiftA", "ShiftB", "Shift",
                 "BlendedNoise", "WeirdScaledSampler", "YClampedGradient", "BlendAlpha", "BlendOffset", "BlendDensity", "EndIslandDensityFunction",
                 "HolderHolder" -> true;
            default -> false;
        };
    }

    /** made only of pure vanilla nodes; a Cache2D (or flat cache) only over a function that ignores y */
    static boolean pure(DensityFunction df) {
        String k = kind(df);
        if (k.equals("Marker")) {
            String t = markerType(df);
            if (t.equals("FlatCache") || t.equals("Cache2D")) return ColumnMemo.yFree(child(df)) && pure(child(df));
            if (t.equals("CacheOnce")) return pure(child(df));
            return false;   // interpolated / cache_all_in_cell inside an interpolated tree: not ours to judge
        }
        if (!pureKind(k)) return false;
        for (DensityFunction c : children(df)) if (!pure(c)) return false;
        return true;
    }

    /** the path to the first node that makes a subtree impure (diagnostics) */
    static String impure(DensityFunction df) {
        String k = kind(df);
        if (k.equals("Marker")) {
            String t = markerType(df);
            if (t.equals("FlatCache") || t.equals("Cache2D")) {
                if (!ColumnMemo.yFree(child(df))) return k + "/" + t + ": inner reads y";
                String in = impure(child(df));
                return in == null ? null : k + "/" + t + " > " + in;
            }
            if (t.equals("CacheOnce")) {
                String in = impure(child(df));
                return in == null ? null : k + "/" + t + " > " + in;
            }
            return k + "/" + t;
        }
        if (!pureKind(k)) return k + " (" + df.getClass().getName() + ")";
        for (DensityFunction c : children(df)) {
            String in = impure(c);
            if (in != null) return k + " > " + in;
        }
        return null;
    }

    /** reads noise that no 2D cache keeps (worth a mark) */
    static boolean readsLiveNoise(DensityFunction df) {
        String k = kind(df);
        if (k.equals("Marker")) {
            String t = markerType(df);
            return !(t.equals("FlatCache") || t.equals("Cache2D")) && readsLiveNoise(child(df));
        }
        if (k.equals("Noise") || k.equals("ShiftedNoise") || k.startsWith("Shift") || k.equals("WeirdScaledSampler") || k.equals("BlendedNoise")
                || k.equals("EndIslandDensityFunction")) return true;
        for (DensityFunction c : children(df)) if (readsLiveNoise(c)) return true;
        return false;
    }

    private static DensityFunction child(DensityFunction marker) {
        return (DensityFunction) Reflect.get(marker, "wrapped");
    }

    /** the density functions a node reads (a holder's value, a spline's coordinates, a record's components) */
    private static List<DensityFunction> children(DensityFunction df) {
        List<DensityFunction> out = new ArrayList<>();
        String k = kind(df);
        if (k.equals("HolderHolder")) {
            out.add(((Holder<DensityFunction>) Reflect.get(df, "function")).value());
            return out;
        }
        if (k.equals("Spline")) {
            spline(Reflect.get(df, "spline"), out);
            return out;
        }
        if (!(df instanceof Record)) return out;
        for (Field f : fields(df.getClass())) {
            try {
                if (f.get(df) instanceof DensityFunction d) out.add(d);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private static void spline(Object s, List<DensityFunction> out) {
        if (!kind(s).equals("Multipoint")) return;
        Object coord = Reflect.get(s, "coordinate");
        out.add(((Holder<DensityFunction>) Reflect.get(coord, "function")).value());
        for (Object v : (List<?>) Reflect.get(s, "values")) spline(v, out);
    }

    private static Field[] fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> out = new ArrayList<>();
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                out.add(f);
            }
            return out.toArray(new Field[0]);
        });
    }

    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ the rewrite

    private static final class Rewriter {
        /** occurrences of each subtree (by value) in all interpolated trees of the final density */
        final Map<DensityFunction, Integer> count = new HashMap<>();
        /** each subtree's rewrite (by value): equal subtrees end as one instance, so as one cache in a NoiseChunk */
        final Map<DensityFunction, DensityFunction> done = new HashMap<>();
        int trees;

        /** outside interpolation: only interpolated markers are opened */
        DensityFunction top(DensityFunction df) {
            List<DensityFunction> inner = new ArrayList<>();
            interpolated(df, inner);
            trees = inner.size();
            for (DensityFunction f : inner) count(f);
            return outside(df);
        }

        private void interpolated(DensityFunction df, List<DensityFunction> out) {
            if (kind(df).equals("Marker") && markerType(df).equals("Interpolated")) {
                out.add(child(df));
                return;
            }
            if (kind(df).equals("Spline")) return;
            for (DensityFunction c : children(df)) interpolated(c, out);
        }

        private void count(DensityFunction df) {
            if (kind(df).equals("HolderHolder")) {
                count(children(df).get(0));
                return;
            }
            if (kind(df).equals("Constant")) return;
            if (count.merge(df, 1, Integer::sum) > 1) return;   // its parts are counted once per distinct subtree
            if (kind(df).equals("Spline")) return;
            if (kind(df).equals("Marker") && !markerType(df).equals("CacheOnce")) return;
            for (DensityFunction c : children(df)) count(c);
        }

        final StringBuilder trace = new StringBuilder();

        private DensityFunction outside(DensityFunction df) {
            String k = kind(df);
            if (WHY) trace.append(k).append(k.equals("Marker") ? "/" + markerType(df) : "").append(' ');
            if (k.equals("HolderHolder")) {   // NoiseChunk.wrap reads a holder as its value (inline JSON functions are holders too)
                DensityFunction v = children(df).get(0);
                DensityFunction w = outside(v);
                return w == v ? df : w;
            }
            if (k.equals("Marker") && markerType(df).equals("Interpolated")) {
                DensityFunction in = child(df);
                DensityFunction w = inside(in);
                return w == in ? df : DensityFunctions.interpolated(w);
            }
            if (k.equals("Spline") || !(df instanceof Record) || !(pureKind(k) || k.equals("Marker"))) return df;
            return rebuild(df, this::outside);
        }

        private DensityFunction inside(DensityFunction df) {
            if (kind(df).equals("HolderHolder")) return inside(children(df).get(0));
            DensityFunction r = done.get(df);
            if (r != null) return r;
            r = mark(df);
            done.put(df, r);
            return r;
        }

        private DensityFunction mark(DensityFunction df) {
            String k = kind(df);
            if (k.equals("Marker")) {
                String t = markerType(df);
                if (!t.equals("CacheOnce")) return df;   // 2D caches stay as they are
                return rebuild(df, this::inside);
            }
            if (!pure(df)) return pureKind(k) && !k.equals("Spline") ? rebuild(df, this::inside) : df;
            if (readsLiveNoise(df)) {
                if (ColumnMemo.yFree(df)) {
                    column++;
                    return DensityFunctions.cache2d(df);
                }
                if (count.getOrDefault(df, 0) > 1) {
                    once++;
                    return DensityFunctions.cacheOnce(k.equals("Spline") ? df : rebuild(df, this::inside));
                }
            }
            return k.equals("Spline") ? df : rebuild(df, this::inside);
        }
    }

    /** a record of density functions with each density function component rewritten (canonical constructor) */
    private static DensityFunction rebuild(DensityFunction df, java.util.function.UnaryOperator<DensityFunction> f) {
        if (!(df instanceof Record)) return df;
        Field[] rc = fields(df.getClass());
        Object[] args = new Object[rc.length];
        Class<?>[] types = new Class<?>[rc.length];
        boolean changed = false;
        try {
            for (int i = 0; i < rc.length; i++) {
                Object v = rc[i].get(df);
                types[i] = rc[i].getType();
                if (v instanceof DensityFunction d && DensityFunction.class.isAssignableFrom(types[i])) {
                    DensityFunction w = f.apply(d);
                    changed |= w != d;
                    v = w;
                }
                args[i] = v;
            }
            if (!changed) return df;
            Constructor<?> c = df.getClass().getDeclaredConstructor(types);
            c.setAccessible(true);
            return (DensityFunction) c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
