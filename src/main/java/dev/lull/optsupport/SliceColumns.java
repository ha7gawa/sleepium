package dev.lull.optsupport;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * WorldgenShare (corners): interpolator corners on a chunk border are computed twice — slice x = 16 of one chunk is
 * slice x = 0 of the next, and the same along z (up to 36 % of all corner work). A corner column is a pure function of
 * the RandomState and the cell column when the Blender is empty and every Cache2D in the interpolated trees wraps a
 * function that ignores y (Cache2D keeps the value of the first y it saw in a column: order-dependent otherwise; vanilla
 * only caches 2D functions there). Border columns are published here and taken by the neighbour.
 *
 * <p>Direct-mapped, 4096 slots of immutable entries (key + one copied column per interpolator), published with
 * release/acquire; a collision just replaces the entry.
 */
public final class SliceColumns {
    private static final int BITS = 12, MASK = (1 << BITS) - 1;
    private static final VarHandle AA = MethodHandles.arrayElementVarHandle(Entry[].class);
    private static final Map<RandomState, ArrayList<SliceColumns>> TABLES = new WeakHashMap<>();

    private record Entry(long key, double[][] cols) {}

    private final int cellNoiseMinY, cellCountY, cellWidth, interpolators;
    private final Entry[] slots = new Entry[1 << BITS];
    public long hits, misses;

    private SliceColumns(int cellNoiseMinY, int cellCountY, int cellWidth, int interpolators) {
        this.cellNoiseMinY = cellNoiseMinY;
        this.cellCountY = cellCountY;
        this.cellWidth = cellWidth;
        this.interpolators = interpolators;
    }

    public static synchronized SliceColumns forState(RandomState state, int cellNoiseMinY, int cellCountY, int cellWidth, int interpolators) {
        ArrayList<SliceColumns> l = TABLES.computeIfAbsent(state, k -> new ArrayList<>(1));
        for (SliceColumns t : l)
            if (t.cellNoiseMinY == cellNoiseMinY && t.cellCountY == cellCountY && t.cellWidth == cellWidth && t.interpolators == interpolators) return t;
        SliceColumns t = new SliceColumns(cellNoiseMinY, cellCountY, cellWidth, interpolators);
        l.add(t);
        return t;
    }

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static int slot(int cellX, int cellZ) {
        int h = cellX * 0x9E3779B1 ^ cellZ * 0x85EBCA77;
        return (h ^ (h >>> 15)) & MASK;
    }

    /** the published column (one array per interpolator, cellCountY + 1 values each), or null */
    public double[][] get(int cellX, int cellZ) {
        Entry e = (Entry) AA.getAcquire(slots, slot(cellX, cellZ));
        return e != null && e.key == key(cellX, cellZ) ? e.cols : null;
    }

    public void put(int cellX, int cellZ, double[][] cols) {
        AA.setRelease(slots, slot(cellX, cellZ), new Entry(key(cellX, cellZ), cols));
    }

    // ---- is every Cache2D in these trees over a function that ignores y? ----

    private static Object get(Object o, String name) {
        return Reflect.get(o, name);
    }

    /** -Dlab.slices.walkEach: the walk for every NoiseChunk again (as before 11-21) */
    public static final boolean WALK_EACH = Boolean.getBoolean("lab.slices.walkEach");
    /** cache2dAreFlat's answers by RandomState and beardifier class (guarded by itself) */
    private static final Map<RandomState, Map<Class<?>, Boolean>> FLAT = new WeakHashMap<>();

    /**
     * cache2dAreFlat for the interpolators of a NoiseChunk with an empty Blender, walked once per RandomState and
     * beardifier class (11-21: the walk, by reflection over the whole router, ran for every NoiseChunk). The same answer
     * by construction: the walk reads only the classes of the nodes and fields of the router's functions (noise yScale,
     * holders, splines). A NoiseChunk's interpolator trees are the RandomState's router mapped by NoiseChunk.wrap, which
     * puts a fresh wrapper of the marker's type (NoiseInterpolator, FlatCache, Cache2D, CacheOnce, CacheAllInCell) where
     * the router has a marker - the same classes for every NoiseChunk -, replaces BlendAlpha / BlendOffset only when
     * the Blender is not empty (excluded: the caller asks only then), unwraps holders (the router's own), and puts the
     * NoiseChunk's beardifier where the router has the beardifier marker: the one input that can differ between
     * NoiseChunks of a RandomState (Beardifier for chunks, the marker for column probes), hence the class in the key.
     */
    public static boolean cache2dAreFlat(RandomState state, Object beardifier, List<DensityFunction> roots) {
        Class<?> kind = beardifier == null ? Void.class : beardifier.getClass();
        synchronized (FLAT) {
            Map<Class<?>, Boolean> m = FLAT.get(state);
            Boolean b = m == null ? null : m.get(kind);
            if (b != null) return b;
        }
        boolean ok = cache2dAreFlat(roots);
        synchronized (FLAT) {
            FLAT.computeIfAbsent(state, k -> new ConcurrentHashMap<>(2)).put(kind, ok);
        }
        return ok;
    }

    /** true if every Cache2D reachable from these functions wraps a y-independent function; false if unsure */
    public static boolean cache2dAreFlat(List<DensityFunction> roots) {
        try {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (DensityFunction f : roots) if (!walk(f, false, seen)) return false;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String simple(Object f) {
        String cn = f.getClass().getName();
        return cn.startsWith("net.minecraft.world.level.levelgen.") ? cn.substring("net.minecraft.world.level.levelgen.".length()) : cn;
    }

    /**
     * Walks the tree; inside a Cache2D (flat = true) every node must be known to ignore y. Outside, only the Cache2D
     * nodes matter, but every child is visited.
     */
    private static boolean walk(Object o, boolean flat, IdentityHashMap<Object, Boolean> seen) {
        if (!flat && seen.put(o, Boolean.TRUE) != null) return true;
        String s = simple(o);
        switch (s) {
            case "NoiseChunk$Cache2D":
                return walk(get(o, "function"), true, seen);
            case "NoiseChunk$FlatCache":   // quantised 2D values: y-independent by construction
                return flat || walk(get(o, "noiseFiller"), false, seen);
            case "NoiseChunk$NoiseInterpolator":
                return !flat && walk(get(o, "noiseFiller"), false, seen);
            case "NoiseChunk$CacheOnce":
                return walk(get(o, "function"), flat, seen);
            case "NoiseChunk$CacheAllInCell":
                return !flat && walk(get(o, "noiseFiller"), false, seen);
            case "DensityFunctions$Constant", "DensityFunctions$BlendAlpha", "DensityFunctions$BlendOffset",
                 "DensityFunctions$BeardifierMarker", "DensityFunctions$EndIslandDensityFunction",
                 "DensityFunctions$ShiftA", "DensityFunctions$ShiftB":
                return true;
            case "DensityFunctions$Shift":   // offsetNoise at (x, y, z)
                return !flat;
            case "DensityFunctions$Noise":
                return !flat || ((Number) get(o, "yScale")).doubleValue() == 0.0;
            case "DensityFunctions$ShiftedNoise":
                return (!flat || ((Number) get(o, "yScale")).doubleValue() == 0.0)
                        && walk(get(o, "shiftX"), flat, seen) && walk(get(o, "shiftY"), flat, seen) && walk(get(o, "shiftZ"), flat, seen);
            case "DensityFunctions$Ap2":
                return walk(get(o, "argument1"), flat, seen) && walk(get(o, "argument2"), flat, seen);
            case "DensityFunctions$MulOrAdd", "DensityFunctions$Mapped", "DensityFunctions$Clamp", "DensityFunctions$BlendDensity":
                return walk(get(o, "input"), flat, seen);
            case "DensityFunctions$RangeChoice":
                return walk(get(o, "input"), flat, seen) && walk(get(o, "whenInRange"), flat, seen) && walk(get(o, "whenOutOfRange"), flat, seen);
            case "DensityFunctions$HolderHolder":
                return walk(((Holder<?>) get(o, "function")).value(), flat, seen);
            case "DensityFunctions$Marker":
                return walk(get(o, "wrapped"), flat, seen);
            case "DensityFunctions$Spline":
                return walkSpline(get(o, "spline"), flat, seen);
            default: {
                if (flat) return false;   // inside a Cache2D: only nodes known to ignore y
                // outside: vanilla leaves, and the one vanilla node with an input we must still search
                if (s.equals("DensityFunctions$YClampedGradient") || s.equals("synth.BlendedNoise") || s.equals("Beardifier")) return true;
                if (s.equals("DensityFunctions$WeirdScaledSampler")) return walk(get(o, "input"), false, seen);
                return false;   // a node we cannot look into could hide a Cache2D
            }
        }
    }

    private static boolean walkSpline(Object spline, boolean flat, IdentityHashMap<Object, Boolean> seen) {
        String s = spline.getClass().getName();
        if (s.endsWith("CubicSpline$Constant")) return true;
        if (!s.endsWith("CubicSpline$Multipoint")) return false;
        Object coord = get(spline, "coordinate");
        if (!coord.getClass().getName().endsWith("DensityFunctions$Spline$Coordinate")) return false;
        if (!walk(((Holder<?>) get(coord, "function")).value(), flat, seen)) return false;
        for (Object v : (List<?>) get(spline, "values")) if (!walkSpline(v, flat, seen)) return false;
        return true;
    }
}
