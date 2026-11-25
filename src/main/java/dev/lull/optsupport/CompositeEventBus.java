package dev.lull.optsupport;

/**
 * Fabric: there is no NeoForge event bus to replace. The counters other classes read stay here (never moved: no
 * listener of a NeoForge event can exist on Fabric).
 */
public final class CompositeEventBus {
    private CompositeEventBus() {}

    public static final boolean STATS = false;
    public static final String[] PATH_NAMES = {};
    public static long fullPosts, lockedPosts;

    public static long[] pathCounts() {
        return new long[0];
    }

    public static String topTypes(int n, long ticks) {
        return "";
    }
}
