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
    private Object[] sec = new Object[256];
    private int[] ox = new int[256], oy = new int[256], oz = new int[256], rid = new int[256], sidx = new int[256];
    private int n, regions, epoch = -1;
    private int[] rx = new int[64], ry = new int[64], rz = new int[64];
    private final Long2IntOpenHashMap regionOf = new Long2IntOpenHashMap();
    private byte[] state = new byte[64];
    private boolean broken;
    /** the setOrigin epoch: any section moved */
    public static volatile int originEpoch;
    static final int SHIFT = Integer.getInteger("lab.frustumRegions.shift", 5), SIZE = 1 << SHIFT;

    public SectionSet(int size) {
        super(size);
        regionOf.defaultReturnValue(-1);
    }

    @Override
    public boolean add(Object node) {
        if (!super.add(node)) return false;
        if (n == sec.length) {
            int c = n * 2;
            sec = java.util.Arrays.copyOf(sec, c);
            ox = java.util.Arrays.copyOf(ox, c);
            oy = java.util.Arrays.copyOf(oy, c);
            oz = java.util.Arrays.copyOf(oz, c);
            rid = java.util.Arrays.copyOf(rid, c);
            sidx = java.util.Arrays.copyOf(sidx, c);
        }
        SectionRenderDispatcher.RenderSection s = ((dev.lull.opt.SectionNodeAccessor) node).sleep$section();
        sec[n] = s;
        sidx[n] = s.index;
        place(n, s);
        n++;
        return true;
    }

    private void place(int i, SectionRenderDispatcher.RenderSection s) {
        var o = s.getOrigin();
        ox[i] = o.getX();
        oy[i] = o.getY();
        oz[i] = o.getZ();
        int x = ox[i] >> SHIFT, y = oy[i] >> SHIFT, z = oz[i] >> SHIFT;
        long key = ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        int r = regionOf.get(key);
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
    }

    @Override public boolean remove(Object o) { broken = true; return super.remove(o); }
    @Override public void clear() { broken = true; super.clear(); }
    @Override public boolean removeAll(Collection<?> c) { broken = true; return super.removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { broken = true; return super.retainAll(c); }
    @Override public boolean removeIf(java.util.function.Predicate<? super Object> f) { broken = true; return super.removeIf(f); }

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
        if (e != epoch) {   // sections moved since: origins and regions read again
            regionOf.clear();
            regions = 0;
            for (int i = 0; i < n; i++) place(i, (SectionRenderDispatcher.RenderSection) sec[i]);
            epoch = e;
        }
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
        return true;
    }
}
