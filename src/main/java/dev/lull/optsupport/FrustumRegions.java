package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;

/**
 * Frustum culling of chunk sections by regions (SectionOcclusionGraph.addSectionsInFrustum, FrustumRegionMixin).
 * Vanilla tests every section of the occlusion graph against the frustum whenever the camera turns by 2 degrees: at
 * view distance 32 tens of thousands of boxes, 2.4 ms a frame on the render thread while the camera keeps turning.
 * Here a region of 4x4x4 sections (64 blocks) is tested once per call: wholly outside a plane - every section in it is
 * skipped; wholly inside every plane - every section in it is taken; only the sections of regions on the frustum's
 * border are tested one by one, as vanilla does. The list keeps vanilla's order, and its contents are the same: a box
 * inside a region outside a plane is outside it too (JOML's testAab and intersectAab compare the same far vertex with
 * the plane), and a box inside a region wholly inside is wholly inside (float rounding is monotonic in each step).
 * Render thread only.
 */
public final class FrustumRegions {
    private FrustumRegions() {}

    private static final byte OUT = 1, IN = 2, PART = 3;
    private static final Long2ByteOpenHashMap STATE = new Long2ByteOpenHashMap(4096);
    private static Frustum current;
    private static FrustumIntersection fi;
    private static double cx, cy, cz;
    public static long calls, regionTests, boxTests, boxesSkipped, applyNs, visibleSum, updateNs, updates, updateAt;
    private static long beginAt;
    /** -Dlab.frustumRegions.off=true: the timing only, vanilla's tests (A/B in one build) */
    private static final boolean OFF = Boolean.getBoolean("lab.frustumRegions.off");

    public static void end(int visible) {
        applyNs += System.nanoTime() - beginAt;
        visibleSum += visible;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "frustum: %d applies %.3f ms each (%d visible each, %d by the flat walk, verify fails %d), %d region tests, %d boxes by region, %d tested; graph updates %d, %.3f ms each",
                calls, applyNs / 1e6 / Math.max(1, calls), visibleSum / Math.max(1, calls), flatWalks, verifyFails, regionTests, boxesSkipped, boxTests, updates, updateNs / 1e6 / Math.max(1, updates));
    }

    /** addSectionsInFrustum begins: a new frustum, the regions' answers forgotten */
    private static java.util.List<?> out;
    private static final boolean VERIFY = Boolean.getBoolean("lab.frustumRegions.verify");
    public static long flatWalks, verifyFails;

    /** the set's walk: the flat arrays fill the list and the loop gets nothing to walk; else vanilla's walk (by regions) */
    public static java.util.Iterator<?> walk(java.util.LinkedHashSet<?> set) {
        if (current == null || !(set instanceof SectionSet s) || !s.addInFrustum(current, out)) return set.iterator();
        flatWalks++;
        if (VERIFY) {   // vanilla's walk beside it: the same list, in the same order
            java.util.List<Object> mine = new java.util.ArrayList<>(out.subList(outStart, out.size()));
            java.util.List<Object> theirs = new java.util.ArrayList<>();
            for (Object node : set) {
                var sec = ((dev.lull.opt.SectionNodeAccessor) node).sleep$section();
                if (current.isVisible(sec.getBoundingBox())) theirs.add(sec);
            }
            if (!mine.equals(theirs)) {
                verifyFails++;
                if (verifyFails < 5) System.out.println("[sleep/frustum] VERIFY: " + mine.size() + " sections against vanilla's " + theirs.size());
            }
        }
        return java.util.Collections.emptyIterator();
    }

    private static int outStart;

    public static void begin(Frustum frustum, java.util.List<?> sections) {
        out = sections;
        outStart = sections.size();
        beginAt = System.nanoTime();
        STATE.clear();
        current = OFF ? null : frustum;
        var acc = (dev.lull.opt.FrustumRegionAccessor) frustum;
        fi = acc.sleep$intersection();
        cx = acc.sleep$camX();
        cy = acc.sleep$camY();
        cz = acc.sleep$camZ();
        calls++;
    }

    /** the vanilla test for a section's box, by its region where the region decides */
    public static boolean visible(Frustum frustum, AABB box) {
        if (frustum != current || box.isInfinite()) return frustum.isVisible(box);
        int rx = Math.floorDiv((int) Math.floor(box.minX), 64), ry = Math.floorDiv((int) Math.floor(box.minY), 64), rz = Math.floorDiv((int) Math.floor(box.minZ), 64);
        double x1 = rx * 64.0, y1 = ry * 64.0, z1 = rz * 64.0;
        if (box.maxX > x1 + 64 || box.maxY > y1 + 64 || box.maxZ > z1 + 64) return frustum.isVisible(box);   // (not inside one region)
        long key = ((long) rx & 0x1FFFFF) << 42 | ((long) ry & 0x1FFFFF) << 21 | ((long) rz & 0x1FFFFF);
        byte st = STATE.get(key);
        if (st == 0) {
            int r = fi.intersectAab((float) (x1 - cx), (float) (y1 - cy), (float) (z1 - cz), (float) (x1 + 64 - cx), (float) (y1 + 64 - cy), (float) (z1 + 64 - cz));
            st = r == FrustumIntersection.INSIDE ? IN : r == FrustumIntersection.INTERSECT ? PART : OUT;
            STATE.put(key, st);
            regionTests++;
        }
        if (st == PART) {
            boxTests++;
            return frustum.isVisible(box);
        }
        boxesSkipped++;
        return st == IN;
    }
}
