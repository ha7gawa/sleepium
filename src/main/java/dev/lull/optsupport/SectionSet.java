package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;

/**
 * The occlusion graph's set of sections (SectionOcclusionGraph.GraphStorage.renderSections, a LinkedHashSet) with the
 * same order kept in flat arrays: the section, its origin, its 32-block region. Vanilla culls the graph by walking the
 * linked set - a node, its section, the section's box: three objects scattered in memory a section, ~130 ns each at
 * view distance 32 (54 000 sections: 7 ms a turn of the camera). Here the walk reads the arrays in order and touches a
 * section only to add it; a region wholly outside or inside the frustum decides all its sections at once (exact: the
 * same set in the same order - see FrustumRegions), the rest are tested on their origins as cubeInFrustum does.
 *
 * <p>The graph only appends to the set (runUpdates); anything else (remove, clear) and the set falls back to vanilla's
 * walk for good. A section's origin moves when the camera moves far (RenderSection.setOrigin): an epoch, the arrays
 * reread then.
 */
public final class SectionSet extends LinkedHashSet<Object> {
    /**
     * the last sets' size and largest section index (11-18): a new set (one per full graph update) is made at that size
     * at once instead of doubling from 256 - in flight at distance 32 the doublings were ~6 MB of garbage an update.
     */
    private static volatile int sizeHint = 256, posHint = 0;
    private Object[] sec = new Object[sizeHint];
    private int[] ox = new int[sec.length], oy = new int[sec.length], oz = new int[sec.length], rid = new int[sec.length], sidx = new int[sec.length];
    private int n, regions, epoch = -1;
    private int[] rx = new int[64], ry = new int[64], rz = new int[64];
    private final Long2IntOpenHashMap regionOf = new Long2IntOpenHashMap();
    private byte[] state = new byte[64];
    private boolean broken;
    /** the setOrigin epoch: any section moved */
    public static volatile int originEpoch;
    /**
     * the sections that moved (RenderSection.index), a ring every set reads on from where it was (11-17: re-placing all
     * 51 000 sections after each camera chunk crossing took 6.5 ms - the hardest orbit's slowest frames; ViewArea moves
     * one strip of them). A set that fell a whole ring behind, or holds a section without an index, re-places all.
     */
    private static final int LOG = 1 << 16;
    private static final int[] MOVED = new int[LOG];
    private static long movedHead;
    private long consumed = -1;
    private int[] posOf = new int[posHint];   // RenderSection.index -> position + 1
    private boolean noIndex;

    /** RenderSection.setOrigin (SectionOriginEpochMixin) */
    public static void moved(int sectionIndex) {
        synchronized (MOVED) {
            MOVED[(int) (movedHead & (LOG - 1))] = sectionIndex;
            movedHead++;
        }
        originEpoch++;
    }
    static final int SHIFT = Integer.getInteger("lab.frustumRegions.shift", 5), SIZE = 1 << SHIFT;

    public SectionSet(int size) {
        super(size);
        regionOf.defaultReturnValue(-1);
        // (each add places its section where it is then: only the moves from now on matter - a new graph's set, made at
        // every full update, needs no re-placement of all)
        synchronized (MOVED) {
            consumed = movedHead;
        }
    }

    /**
     * OcclusionFast (lab.occlusionFast): the set's own arrays are the set - a node is in it when its section's index is
     * (graph nodes are equal by their section's origin: one node a section), no hashing of nodes (21 % of the graph's
     * walk). Reads come from the arrays in order; anything else than add moves the nodes into LinkedHashSet for good.
     */
    private final boolean own = OcclusionFast.ON;
    private Object[] nodeArr = own ? new Object[sec.length] : null;
    private boolean delegated;

    private void delegate() {
        if (!own || delegated) return;
        delegated = true;
        for (int i = 0; i < n; i++) super.add(nodeArr[i]);
    }

    private boolean arrays() {
        return own && !delegated;
    }

    @Override
    public boolean add(Object node) {
        if (arrays()) {
            int si = ((dev.lull.opt.SectionNodeAccessor) node).sleep$section().index;
            if (si < 0) delegate();
            else if (si < posOf.length && posOf[si] != 0) return false;
        }
        if (!arrays() && !super.add(node)) return false;
        if (n == sec.length) {
            int c = n * 2;
            sec = java.util.Arrays.copyOf(sec, c);
            ox = java.util.Arrays.copyOf(ox, c);
            oy = java.util.Arrays.copyOf(oy, c);
            oz = java.util.Arrays.copyOf(oz, c);
            rid = java.util.Arrays.copyOf(rid, c);
            sidx = java.util.Arrays.copyOf(sidx, c);
            if (own) nodeArr = java.util.Arrays.copyOf(nodeArr, c);
        }
        if (own) nodeArr[n] = node;
        SectionRenderDispatcher.RenderSection s = ((dev.lull.opt.SectionNodeAccessor) node).sleep$section();
        sec[n] = s;
        sidx[n] = s.index;
        if (s.index < 0) noIndex = true;
        else {
            if (s.index >= posOf.length) {
                posOf = java.util.Arrays.copyOf(posOf, Math.max(s.index + 1, posOf.length * 2));
                if (posOf.length > posHint) posHint = posOf.length;
            }
            posOf[s.index] = n + 1;
        }
        place(n, s);
        n++;
        if (n > sizeHint) sizeHint = sec.length;
        return true;
    }

    private void place(int i, SectionRenderDispatcher.RenderSection s) {
        var o = s.getOrigin();
        ox[i] = o.getX();
        oy[i] = o.getY();
        oz[i] = o.getZ();
        int x = ox[i] >> SHIFT, y = oy[i] >> SHIFT, z = oz[i] >> SHIFT;
        long key = ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        int r;
        int slot = -1;
        if (own) {   // OcclusionFast: a direct-mapped slot in front of the map (the same ids: the map gives them out)
            if (slotKey == null) {
                slotKey = new long[SLOTS];
                slotId = new int[SLOTS];
                java.util.Arrays.fill(slotId, -1);
            }
            slot = ((x & 31) << 9) | ((z & 31) << 4) | (y & 15);
            if (slotId[slot] >= 0 && slotKey[slot] == key) {
                rid[i] = slotId[slot];
                return;
            }
        }
        r = regionOf.get(key);
        if (r < 0) {
            r = regions++;
            if (r == rx.length) {
                rx = java.util.Arrays.copyOf(rx, r * 2);
                ry = java.util.Arrays.copyOf(ry, r * 2);
                rz = java.util.Arrays.copyOf(rz, r * 2);
            }
            rx[r] = x << SHIFT;
            ry[r] = y << SHIFT;
            rz[r] = z << SHIFT;
            regionOf.put(key, r);
        }
        rid[i] = r;
        if (slot >= 0) {
            slotKey[slot] = key;
            slotId[slot] = r;
        }
    }

    private static final int SLOTS = 32 * 32 * 16;
    private long[] slotKey;
    private int[] slotId;

    @Override public boolean remove(Object o) { delegate(); broken = true; return super.remove(o); }
    @Override public void clear() { delegate(); broken = true; super.clear(); }
    @Override public boolean removeAll(Collection<?> c) { delegate(); broken = true; return super.removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { delegate(); broken = true; return super.retainAll(c); }
    @Override public boolean removeIf(java.util.function.Predicate<? super Object> f) { delegate(); broken = true; return super.removeIf(f); }

    // reads, from the arrays while they are the set
    @Override public int size() { return arrays() ? n : super.size(); }
    @Override public boolean isEmpty() { return arrays() ? n == 0 : super.isEmpty(); }

    @Override
    public boolean contains(Object o) {
        if (!arrays()) return super.contains(o);
        if (!(o instanceof dev.lull.opt.SectionNodeAccessor a)) return false;
        int si = a.sleep$section().index;
        return si >= 0 && si < posOf.length && posOf[si] != 0;
    }

    @Override
    public java.util.Iterator<Object> iterator() {
        if (!arrays()) return super.iterator();
        return new java.util.Iterator<>() {
            int i;
            @Override public boolean hasNext() { return i < n; }
            @Override public Object next() {
                if (i >= n) throw new java.util.NoSuchElementException();
                return nodeArr[i++];
            }
        };
    }

    @Override public Object[] toArray() { return arrays() ? java.util.Arrays.copyOf(nodeArr, n) : super.toArray(); }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        if (!arrays()) return super.toArray(a);
        if (a.length < n) a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), n);
        System.arraycopy(nodeArr, 0, a, 0, n);
        if (a.length > n) a[n] = null;
        return a;
    }

    @Override
    public void forEach(java.util.function.Consumer<? super Object> action) {
        if (!arrays()) {
            super.forEach(action);
            return;
        }
        for (int i = 0; i < n; i++) action.accept(nodeArr[i]);
    }

    @Override
    public java.util.Spliterator<Object> spliterator() {
        return arrays() ? java.util.Spliterators.spliterator(java.util.Arrays.copyOf(nodeArr, n), java.util.Spliterator.DISTINCT | java.util.Spliterator.ORDERED) : super.spliterator();
    }

    public boolean usable() {
        return !broken && n == size();
    }

    /**
     * addSectionsInFrustum's walk on the arrays: the sections in the frustum, in the set's order, into out (vanilla's
     * list); false when this set cannot answer (then vanilla walks it).
     */
    @SuppressWarnings("unchecked")
    public boolean addInFrustum(Frustum frustum, List<?> out) {
        if (!usable()) return false;
        int e = originEpoch;
        long t0 = System.nanoTime();
        if (e != epoch) {   // sections moved since: the moved ones placed again (or all)
            boolean full = consumed < 0 || noIndex || regions > Math.max(4096, n);
            long head;
            int moved = 0;
            synchronized (MOVED) {
                head = movedHead;
                if (!full && head - consumed > LOG) full = true;
                if (!full)
                    for (long q = consumed; q < head; q++) {
                        int si = MOVED[(int) (q & (LOG - 1))];
                        int p = si < posOf.length ? posOf[si] - 1 : -1;
                        if (p >= 0) {
                            place(p, (SectionRenderDispatcher.RenderSection) sec[p]);
                            moved++;
                        }
                    }
            }
            if (full) {
                regionOf.clear();
                regions = 0;
                if (slotId != null) java.util.Arrays.fill(slotId, -1);
                for (int i = 0; i < n; i++) place(i, (SectionRenderDispatcher.RenderSection) sec[i]);
                places++;
                placeNs += System.nanoTime() - t0;
            } else {
                partialPlaces++;
                partialPlaced += moved;
                partialNs += System.nanoTime() - t0;
            }
            consumed = head;
            epoch = e;
        }
        long t1 = System.nanoTime();
        walks++;
        walkSections += n;
        var acc = (dev.lull.opt.FrustumRegionAccessor) frustum;
        FrustumIntersection fi = acc.sleep$intersection();
        double cx = acc.sleep$camX(), cy = acc.sleep$camY(), cz = acc.sleep$camZ();
        if (state.length < regions) state = new byte[regions * 2];
        java.util.Arrays.fill(state, 0, regions, (byte) 0);
        List<Object> list = (List<Object>) out;
        SectionLists.walkBegin(list.size(), n);
        Object[] wr = SectionLists.walkRefs;
        int[] wi = SectionLists.walkIdx;
        int k = 0;
        for (int i = 0; i < n; i++) {
            int r = rid[i];
            byte st = state[r];
            if (st == 0) {
                int x = rx[r], y = ry[r], z = rz[r];
                int res = fi.intersectAab((float) (x - cx), (float) (y - cy), (float) (z - cz), (float) (x + SIZE - cx), (float) (y + SIZE - cy), (float) (z + SIZE - cz));
                st = res == FrustumIntersection.INSIDE ? (byte) 2 : res == FrustumIntersection.INTERSECT ? (byte) 3 : (byte) 1;
                state[r] = st;
            }
            if (st == 1) continue;
            if (st == 3) {   // on the border: the section's own test, as Frustum.cubeInFrustum
                double x = ox[i], y = oy[i], z = oz[i];
                if (!fi.testAab((float) (x - cx), (float) (y - cy), (float) (z - cz), (float) (x + 16 - cx), (float) (y + 16 - cy), (float) (z + 16 - cz))) continue;
            }
            list.add(sec[i]);
            wr[k] = sec[i];
            wi[k++] = sidx[i];
        }
        SectionLists.walkN = k;
        walkNs += System.nanoTime() - t1;
        return true;
    }

    /** the walks' parts: re-placing all sections after they moved, and the walk itself */
    public static long placeNs, places, walkNs, walks, walkSections, partialPlaces, partialPlaced, partialNs;

    public static String timing() {
        return String.format(java.util.Locale.ROOT, "frustum walk: %d walks %.0f us each over %d sections; %d re-placements of all %.0f us each, %d of the moved ones %.0f us each (%d sections each)",
                walks, walkNs / 1e3 / Math.max(1, walks), walkSections / Math.max(1, walks), places, placeNs / 1e3 / Math.max(1, places),
                partialPlaces, partialNs / 1e3 / Math.max(1, partialPlaces), partialPlaced / Math.max(1, partialPlaces));
    }
}
