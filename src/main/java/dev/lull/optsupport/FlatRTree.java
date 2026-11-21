package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.world.level.biome.Climate;

/**
 * ClimateSearch: Climate.RTree's nearest-leaf search over flat arrays. Vanilla walks Node objects whose 7 parameter
 * ranges are 7 Parameter records each (a pointer chase per range) through an interface metric, recursively; the walk
 * here is the same branch and bound over the same nodes in the same order, with the ranges of a node next to each other
 * in one long[]: the same distances, the same comparisons, so the same leaf, ties included.
 *
 * <p>Equivalence with SubTree.search: a child is entered when the best distance so far is greater than the child's
 * distance; a leaf child then becomes the best; a subtree child returns either the best it was given (no change) or a
 * strictly nearer leaf, which becomes the best. The best distance is passed down instead of being computed again at
 * every level (vanilla's metric.distance(leaf) is a pure function of the leaf and the target: the same value). A
 * distance is summed in vanilla's order and given up once the partial sum reaches the bound: all terms are squares, so
 * the full sum would not be below the bound either and the comparison has the same outcome. (Vanilla adds the terms in
 * the same order; long additions of non-negative squares are exact in any order anyway.)
 *
 * <p>The warm start is vanilla's own per-thread lastResult of that tree, read and written as vanilla does, so a search
 * through vanilla's path (another metric) and one through here see the same history.
 */
public final class FlatRTree {
    private static final Field INDEX, ROOT, LAST, SPACE, CHILDREN, VALUE;

    static {
        try {
            INDEX = Climate.ParameterList.class.getDeclaredField("index");
            Class<?> tree = INDEX.getType();
            ROOT = tree.getDeclaredField("root");
            LAST = tree.getDeclaredField("lastResult");
            Class<?> node = ROOT.getType();
            SPACE = node.getDeclaredField("parameterSpace");
            Class<?> sub = Class.forName(tree.getName() + "$SubTree");
            Class<?> leaf = Class.forName(tree.getName() + "$Leaf");
            CHILDREN = sub.getDeclaredField("children");
            VALUE = leaf.getDeclaredField("value");
            for (Field f : new Field[]{INDEX, ROOT, LAST, SPACE, CHILDREN, VALUE}) f.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ClimateSearch: Climate.RTree's fields not found", e);
        }
    }

    /** per node: min0, max0, ... min6, max6 */
    private final long[] box;
    /** per node: its first child's id (children are numbered consecutively), -1 for a leaf */
    private final int[] first;
    private final int[] count;
    /** per node: the vanilla Node object (a Leaf for leaves) and, for leaves, its value */
    private final Object[] nodes;
    private final Object[] values;
    private final IdentityHashMap<Object, Integer> ids;
    private final ThreadLocal<Object> last;

    @SuppressWarnings("unchecked")
    private FlatRTree(Object tree) throws ReflectiveOperationException {
        Object root = ROOT.get(tree);
        last = (ThreadLocal<Object>) LAST.get(tree);
        List<Object> order = new ArrayList<>();
        List<int[]> links = new ArrayList<>();
        order.add(root);
        // breadth first: the children of a node get consecutive ids, in vanilla's child order
        for (int n = 0; n < order.size(); n++) {
            Object o = order.get(n);
            Object[] kids = o.getClass() == CHILDREN.getDeclaringClass() ? (Object[]) CHILDREN.get(o) : null;
            if (kids == null) {
                links.add(new int[]{-1, 0});
            } else {
                links.add(new int[]{order.size(), kids.length});
                for (Object k : kids) order.add(k);
            }
        }
        int size = order.size();
        box = new long[size * 14];
        first = new int[size];
        count = new int[size];
        nodes = order.toArray();
        values = new Object[size];
        ids = new IdentityHashMap<>();
        for (int n = 0; n < size; n++) {
            Object o = nodes[n];
            Climate.Parameter[] space = (Climate.Parameter[]) SPACE.get(o);
            for (int d = 0; d < 7; d++) {
                box[n * 14 + d * 2] = space[d].min();
                box[n * 14 + d * 2 + 1] = space[d].max();
            }
            first[n] = links.get(n)[0];
            count[n] = links.get(n)[1];
            if (first[n] < 0) {
                values[n] = VALUE.get(o);
                ids.put(o, n);
            }
        }
    }

    public static FlatRTree of(Climate.ParameterList<?> list) {
        try {
            return new FlatRTree(INDEX.get(list));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Node.distance, given up (any value not below the bound) once the partial sum reaches the bound: checked once, after
     * the first four ranges (fewer branches; the sum is the same either way).
     */
    private long dist(int n, long t0, long t1, long t2, long t3, long t4, long t5, long bound) {
        long[] b = box;
        int o = n * 14;
        long s = sq(b[o], b[o + 1], t0) + sq(b[o + 2], b[o + 3], t1) + sq(b[o + 4], b[o + 5], t2) + sq(b[o + 6], b[o + 7], t3);
        if (s >= bound) return s;
        return s + sq(b[o + 8], b[o + 9], t4) + sq(b[o + 10], b[o + 11], t5) + sq(b[o + 12], b[o + 13], 0L);
    }

    /** Mth.square(Parameter.distance(v)) */
    private static long sq(long min, long max, long v) {
        long i = v - max;
        long j = min - v;
        long d = i > 0L ? i : Math.max(j, 0L);
        return d * d;
    }

    /** a strictly nearer leaf than the bound under subtree n, or -1 */
    private int sub(int n, long t0, long t1, long t2, long t3, long t4, long t5, long bound) {
        int best = -1;
        long bd = bound;
        for (int c = first[n], e = c + count[n]; c < e; c++) {
            long j = dist(c, t0, t1, t2, t3, t4, t5, bd);
            if (bd > j) {
                if (first[c] < 0) {
                    best = c;
                    bd = j;
                } else {
                    int r = sub(c, t0, t1, t2, t3, t4, t5, bd);
                    if (r >= 0) {
                        best = r;
                        bd = dist(r, t0, t1, t2, t3, t4, t5, Long.MAX_VALUE);
                    }
                }
            }
        }
        return best;
    }

    /** RTree.search(target, Node::distance): the value of the nearest leaf, lastResult updated as vanilla does */
    public Object search(Climate.TargetPoint p) {
        long t0 = p.temperature(), t1 = p.humidity(), t2 = p.continentalness(), t3 = p.erosion(), t4 = p.depth(), t5 = p.weirdness();
        int leaf;
        Object warm = last.get();
        if (first[0] < 0) {
            leaf = 0;   // a single leaf: Leaf.search returns itself
        } else {
            int w = -1;
            long bound = Long.MAX_VALUE;
            if (warm != null) {
                w = ids.get(warm);
                bound = dist(w, t0, t1, t2, t3, t4, t5, Long.MAX_VALUE);
            }
            int r = sub(0, t0, t1, t2, t3, t4, t5, bound);
            leaf = r >= 0 ? r : w;
        }
        // the same value stays: no set (ThreadLocal.set scans for stale entries; most searches end on their warm start)
        if (nodes[leaf] != warm) last.set(nodes[leaf]);
        return values[leaf];
    }
}
