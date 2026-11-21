package dev.lull.optsupport;

import java.util.concurrent.atomic.LongAdder;

/**
 * Measurement only ({@code -Dlab.save.stats=true}): exact bytes allocated and time spent by each part of a chunk save,
 * per thread (ThreadMXBean's allocated-bytes counter around the part), printed at close. Parts: main write (all of
 * ChunkSerializer.write on the main thread), of it the palettes (their copies / snapshots), main store, pool fill (the
 * palettes encoded), pool record (ChunkIo's encode and deflate); also the light storage's published snapshots (LightSnapshot).
 */
public final class SaveStats {
    private SaveStats() {}

    public static final boolean ON = Boolean.getBoolean("lab.save.stats");
    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    public static final int WRITE = 0, PALETTES = 1, STORE = 2, FILL = 3, RECORD = 4, LIGHT = 5;
    private static final String[] NAMES = {"main write", "  of it palettes", "main store", "pool fill", "pool record", "light snapshot"};
    private static final LongAdder[] N = new LongAdder[6], BYTES = new LongAdder[6], NS = new LongAdder[6];

    static {
        for (int i = 0; i < 6; i++) {
            N[i] = new LongAdder();
            BYTES[i] = new LongAdder();
            NS[i] = new LongAdder();
        }
    }

    public static long bytes() {
        return MX.getCurrentThreadAllocatedBytes();
    }

    public static void add(int part, long bytes0, long ns0) {
        N[part].increment();
        BYTES[part].add(bytes() - bytes0);
        NS[part].add(System.nanoTime() - ns0);
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("save stats:");
        for (int i = 0; i < 6; i++) {
            long n = N[i].sum();
            if (n == 0) continue;
            sb.append(String.format(java.util.Locale.ROOT, " | %s %d x %.1f KB %.1f us (total %.1f MB, %.0f ms)", NAMES[i], n,
                    BYTES[i].sum() / 1024.0 / n, NS[i].sum() / 1e3 / n, BYTES[i].sum() / 1048576.0, NS[i].sum() / 1e6));
        }
        return sb.toString();
    }
}
