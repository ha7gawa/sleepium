package dev.lull.sleepium.server;

import java.util.ArrayList;
import java.util.List;

/**
 * IntCache's pools, one set per thread (IntCacheThreadMixin). Vanilla keeps one static set: resetIntCache - called at
 * the start of every biome query - hands every array back, so two threads querying biomes would hand each other arrays
 * still in use. Per thread the code is vanilla's, line for line: the main thread sees exactly the arrays it saw before
 * (no other thread uses its pools), a worker its own.
 */
public final class IntCachePools {
    private int intCacheSize = 256;
    private final List<int[]> freeSmallArrays = new ArrayList<>();
    private final List<int[]> inUseSmallArrays = new ArrayList<>();
    private final List<int[]> freeLargeArrays = new ArrayList<>();
    private final List<int[]> inUseLargeArrays = new ArrayList<>();

    private static final ThreadLocal<IntCachePools> POOLS = ThreadLocal.withInitial(IntCachePools::new);

    public static IntCachePools get() {
        return POOLS.get();
    }

    public int[] getIntCache(int size) {
        if (size <= 256) {
            if (freeSmallArrays.isEmpty()) {
                int[] a = new int[256];
                inUseSmallArrays.add(a);
                return a;
            } else {
                int[] a = freeSmallArrays.remove(freeSmallArrays.size() - 1);
                inUseSmallArrays.add(a);
                return a;
            }
        } else if (size > intCacheSize) {
            intCacheSize = size;
            freeLargeArrays.clear();
            inUseLargeArrays.clear();
            int[] a = new int[intCacheSize];
            inUseLargeArrays.add(a);
            return a;
        } else if (freeLargeArrays.isEmpty()) {
            int[] a = new int[intCacheSize];
            inUseLargeArrays.add(a);
            return a;
        } else {
            int[] a = freeLargeArrays.remove(freeLargeArrays.size() - 1);
            inUseLargeArrays.add(a);
            return a;
        }
    }

    public void resetIntCache() {
        if (!freeLargeArrays.isEmpty()) freeLargeArrays.remove(freeLargeArrays.size() - 1);
        if (!freeSmallArrays.isEmpty()) freeSmallArrays.remove(freeSmallArrays.size() - 1);
        freeLargeArrays.addAll(inUseLargeArrays);
        freeSmallArrays.addAll(inUseSmallArrays);
        inUseLargeArrays.clear();
        inUseSmallArrays.clear();
    }

    public String getCacheSizes() {
        return "cache: " + freeLargeArrays.size() + ", tcache: " + freeSmallArrays.size() + ", allocated: " + inUseLargeArrays.size() + ", tallocated: " + inUseSmallArrays.size();
    }
}
