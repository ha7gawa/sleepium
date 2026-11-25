package dev.lull.optsupport;

/** Fabric (lab): NeoForge's event sites (no events on Fabric) is not in the Fabric build yet; what other classes read stays, off. */
public final class EventSites {
    private EventSites() {}

    public static int renderListenedMask() {
        return 0;
    }

    public static boolean postsListened(Class<?> renderer, int mask) {
        return false;
    }

    public static String report() {
        return "";
    }
}
