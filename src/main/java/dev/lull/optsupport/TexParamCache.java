package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * The integer texture parameters last set through GlStateManager, per GL texture name (lab, 11-20). Vanilla 1.20.1's
 * AbstractTexture.setFilter sets MIN / MAG filter on every bind whether they changed or not - apitrace on the Radeon
 * boxes: ~90 glTexParameteri a frame, 98 % of them setting what is already set, each a call into the GL driver (AMD's
 * driver is a third of the render thread on a 4-core Xeon). A parameter equal to the one last set for the bound texture
 * is skipped. Render thread only (all GL is). Names are forgotten when the texture is deleted (GL reuses them).
 */
public final class TexParamCache {
    private static final Long2IntOpenHashMap SET = new Long2IntOpenHashMap();
    private static final int[] BOUND = new int[32];
    private static int unit;
    public static long skipped, passed;

    static {
        SET.defaultReturnValue(Integer.MIN_VALUE);
    }

    private TexParamCache() {}

    public static void activeTexture(int glUnit) {
        int u = glUnit - 0x84C0;   // GL_TEXTURE0
        unit = u >= 0 && u < BOUND.length ? u : 0;
    }

    public static void bound(int texture) {
        BOUND[unit] = texture;
    }

    /** true: the call can be skipped (the bound 2D texture already has this value) */
    public static boolean same(int target, int pname, int param) {
        if (target != 0x0DE1) return false;   // GL_TEXTURE_2D only
        long key = ((long) BOUND[unit] << 32) | (pname & 0xFFFFFFFFL);
        if (SET.get(key) == param) {
            skipped++;
            return true;
        }
        SET.put(key, param);
        passed++;
        return false;
    }

    /** a float parameter or another path changed this one: forget it */
    public static void changed(int target, int pname) {
        if (target == 0x0DE1) SET.remove(((long) BOUND[unit] << 32) | (pname & 0xFFFFFFFFL));
    }

    public static void deleted(int texture) {
        if (SET.isEmpty()) return;
        long hi = (long) texture << 32;
        SET.keySet().removeIf(k -> (k & 0xFFFFFFFF00000000L) == hi);
    }
}
