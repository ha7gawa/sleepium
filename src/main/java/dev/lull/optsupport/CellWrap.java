package dev.lull.optsupport;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * lab.opt.NoiseCellWrapMixin: NoiseChunk's constructor maps its router (pass 1) and then maps the mapped final density
 * once more into the cell cache, {@code cacheAllInCell(add(finalDensity, beardifier marker)).mapAll(this::wrap)}
 * (pass 2). Pass 2 rebuilds every node of the already mapped tree - the overworld's splines again with a stream, a
 * list and a coordinate per point - and hashes each rebuilt subtree into the wrap map, only to get back what it
 * started from: a mapped tree maps to itself.
 *
 * <p>Why it maps to itself (empty Blender, every node of a kind listed here): a pass-1 node that ends in
 * visitor.apply is the wrap map's key for its own value (wrapNew returns a non-marker unchanged; equal later keys get
 * that first instance), so rebuilding it from children that map to themselves gives an equal record and wrap returns
 * it. A NoiseChunk wrapper (interpolator, flat cache, 2D cache, cache once, cell cache) maps through
 * MarkerOrMarked.mapAll to Marker(type, wrapped mapped) - equal to the key it was made for, so wrap returns the same
 * wrapper. Clamp, Mapped and MulOrAdd rebuild without wrap: an equal record of the same parts (min / max recomputed
 * from equal inputs). Leaves map to themselves; a spline rebuilds its points from coordinates that map to themselves
 * (the same locations and derivative arrays, min / max recomputed from equal values). None of these lookups misses, so
 * pass 2's walk changes nothing in the NoiseChunk: no wrapper is created, none is reordered. Excluded: a non-empty
 * Blender (BlendAlpha / BlendOffset map to the chunk's flat caches, whose remap wraps them again), holders (wrap
 * unwraps them, pass 1 leaves none), unknown kinds (mods' functions, ColumnMemo's per-chunk memo: a new instance on
 * every mapping). The kinds are judged on the mapped tree of the first NoiseChunk of each router and beardifier class
 * (NoiseChunk.wrap gives every NoiseChunk of a router the same kinds; the beardifier is the one input that differs).
 */
public final class CellWrap {
    private CellWrap() {}

    public static long used, vanilla;

    /** DensityFunctions.BeardifierMarker.INSTANCE (protected) */
    public static final DensityFunction BEARDIFIER_MARKER;

    static {
        DensityFunction m = null;
        try {
            Class<?> c = Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker");
            m = (DensityFunction) c.getEnumConstants()[0];
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[sleep/opt] cell wrap: no beardifier marker (" + e + ")");
        }
        BEARDIFIER_MARKER = m;
    }

    private static final Map<NoiseRouter, Map<Class<?>, Boolean>> SAFE = new WeakHashMap<>();

    /** may pass 2 be taken as mapping the mapped final density to itself? (empty Blender checked by the caller) */
    public static boolean selfMapping(NoiseRouter router, Object beardifier, DensityFunction mappedFinal) {
        Class<?> kind = beardifier == null ? Void.class : beardifier.getClass();
        synchronized (SAFE) {
            Map<Class<?>, Boolean> m = SAFE.get(router);
            Boolean b = m == null ? null : m.get(kind);
            if (b != null) return b;
        }
        boolean ok;
        try {
            ok = walk(mappedFinal, new IdentityHashMap<>());
        } catch (RuntimeException e) {
            ok = false;
        }
        synchronized (SAFE) {
            SAFE.computeIfAbsent(router, k -> new ConcurrentHashMap<>(2)).put(kind, ok);
        }
        System.out.println("[sleep/opt] cell wrap: " + (ok ? "the mapped final density maps to itself" : "pass 2 left as vanilla") + " (beardifier "
                + kind.getSimpleName() + ")");
        return ok;
    }

    private static Object get(Object o, String name) {
        return Reflect.get(o, name);
    }

    private static String simple(Object f) {
        String cn = f.getClass().getName();
        return cn.startsWith("net.minecraft.world.level.levelgen.") ? cn.substring("net.minecraft.world.level.levelgen.".length()) : cn;
    }

    private static boolean walk(Object o, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(o, Boolean.TRUE) != null) return true;
        switch (simple(o)) {
            case "NoiseChunk$NoiseInterpolator", "NoiseChunk$FlatCache", "NoiseChunk$CacheAllInCell":
                return walk(get(o, "noiseFiller"), seen);
            case "NoiseChunk$Cache2D", "NoiseChunk$CacheOnce":
                return walk(get(o, "function"), seen);
            case "DensityFunctions$Constant", "DensityFunctions$BlendAlpha", "DensityFunctions$BlendOffset", "DensityFunctions$BeardifierMarker",
                 "DensityFunctions$EndIslandDensityFunction", "DensityFunctions$ShiftA", "DensityFunctions$ShiftB", "DensityFunctions$Shift",
                 "DensityFunctions$Noise", "DensityFunctions$YClampedGradient", "synth.BlendedNoise", "Beardifier":
                return true;
            case "DensityFunctions$ShiftedNoise":
                return walk(get(o, "shiftX"), seen) && walk(get(o, "shiftY"), seen) && walk(get(o, "shiftZ"), seen);
            case "DensityFunctions$Ap2":
                return walk(get(o, "argument1"), seen) && walk(get(o, "argument2"), seen);
            case "DensityFunctions$MulOrAdd", "DensityFunctions$Mapped", "DensityFunctions$Clamp", "DensityFunctions$BlendDensity",
                 "DensityFunctions$WeirdScaledSampler":
                return walk(get(o, "input"), seen);
            case "DensityFunctions$RangeChoice":
                return walk(get(o, "input"), seen) && walk(get(o, "whenInRange"), seen) && walk(get(o, "whenOutOfRange"), seen);
            case "DensityFunctions$Spline":
                return spline(get(o, "spline"), seen);
            default:
                return false;   // holders, markers, mods' kinds, ColumnMemo: not known to map to themselves
        }
    }

    private static boolean spline(Object s, IdentityHashMap<Object, Boolean> seen) {
        String n = s.getClass().getName();
        if (n.equals("net.minecraft.util.CubicSpline$Constant")) return true;
        if (!n.equals("net.minecraft.util.CubicSpline$Multipoint")) return false;
        Object coord = get(s, "coordinate");
        if (!coord.getClass().getName().equals("net.minecraft.world.level.levelgen.DensityFunctions$Spline$Coordinate")) return false;
        Holder<?> h = (Holder<?>) get(coord, "function");
        if (!(h instanceof Holder.Direct<?>) || !walk(h.value(), seen)) return false;
        for (Object v : (List<?>) get(s, "values")) if (!spline(v, seen)) return false;
        return true;
    }
}
