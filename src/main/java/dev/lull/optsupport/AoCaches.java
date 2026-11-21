package dev.lull.optsupport;

/**
 * AoCacheSlotMixin / AoCacheSlotFaceMixin: ModelBlockRenderer.CACHE (the per-thread light / shade cache of AO
 * meshing, asked for every face) taken from the ThreadLocal once per pool thread and kept in its slot - the very same
 * object, without the ThreadLocal lookup each time.
 */
public final class AoCaches {
    private AoCaches() {}

    public static Object get(ThreadLocal<?> cache) {
        Object[] s = ThreadSlots.mine();
        if (s == null) return cache.get();
        Object c = s[ThreadSlots.AO_CACHE];
        if (c == null) s[ThreadSlots.AO_CACHE] = c = cache.get();
        return c;
    }
}
