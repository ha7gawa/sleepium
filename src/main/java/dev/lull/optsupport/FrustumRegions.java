package dev.lull.optsupport;

import net.minecraft.client.renderer.culling.Frustum;

/**
 * Frustum culling of chunk sections on flat arrays (FrustumRegionMixin, SectionSet). Vanilla re-culls the occlusion
 * graph whenever the camera turns by 2 degrees, walking its linked set of nodes and reading each section's box: at view
 * distance 32 tens of thousands of objects scattered in memory, ~7 ms a turn on the render thread. Here the set keeps
 * the sections' origins and 32-block regions in arrays in the set's order; a region wholly outside or inside the
 * frustum decides all its sections, the rest are tested one by one as cubeInFrustum does. The list gets the same
 * sections in the same order (-Dlab.frustumRegions.verify=true compares with vanilla's walk every time): a box inside a
 * region outside a plane is outside it too (JOML's testAab and intersectAab compare the same far vertex with the
 * plane), and a box inside a region wholly inside is wholly inside (float rounding is monotonic in each step). Render
 * thread only.
 */
public final class FrustumRegions {
    private FrustumRegions() {}

    private static Frustum current;
    private static java.util.List<?> out;
    public static long calls, applyNs, visibleSum, flatWalks, verifyFails;
    private static long beginAt;
    private static final boolean VERIFY = Boolean.getBoolean("lab.frustumRegions.verify");
    /** -Dlab.frustumRegions.off=true: the timing only, vanilla's walk (A/B in one build) */
    private static final boolean OFF = Boolean.getBoolean("lab.frustumRegions.off");

    public static void begin(Frustum frustum, java.util.List<?> sections) {
        out = sections;
        current = OFF ? null : frustum;
        beginAt = System.nanoTime();
        calls++;
    }

    /** the set's walk: the flat arrays fill the list and the loop gets nothing to walk; else vanilla's walk */
    public static java.util.Iterator<?> walk(java.util.LinkedHashSet<?> set) {
        int from = out == null ? 0 : out.size();
        if (current == null || out == null || !(set instanceof SectionSet s) || !s.addInFrustum(current, out)) return set.iterator();
        flatWalks++;
        if (VERIFY) {   // vanilla's walk beside it: the same list, in the same order
            java.util.List<Object> mine = new java.util.ArrayList<>(out.subList(from, out.size()));
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

    public static void end(int visible) {
        applyNs += System.nanoTime() - beginAt;
        visibleSum += visible;
        current = null;
        out = null;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "frustum: %d applies %.3f ms each (%d visible each, %d by the flat walk, verify fails %d)",
                calls, applyNs / 1e6 / Math.max(1, calls), visibleSum / Math.max(1, calls), flatWalks, verifyFails);
    }
}
