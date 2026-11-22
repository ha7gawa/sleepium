package dev.lull.optsupport;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measurement only ({@code -Dlab.save.stats=true}): exact bytes allocated and time spent by each part of a chunk save,
 * per thread (ThreadMXBean's allocated-bytes counter around the part), printed at close and every
 * {@code lab.save.statsEvery} seconds (default 30) while saves happen (a stop that throws before close printed nothing).
 * Parts: main write (all of ChunkSerializer.write on the main thread), of it the palettes (their copies / snapshots), of
 * it the block entities (the loop's tags made on main + the pool fork's wall time), main store, pool fill (the palettes
 * encoded), pool record (ChunkIo's encode and deflate); also the light storage's published snapshots (LightSnapshot).
 * Per block-entity class: saves, time and bytes of each tag (on main or on the pool), the top classes printed.
 */
public final class SaveStats {
    private SaveStats() {}

    public static final boolean ON = Boolean.getBoolean("lab.save.stats");
    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    public static final int WRITE = 0, PALETTES = 1, STORE = 2, FILL = 3, RECORD = 4, LIGHT = 5, BE = 6, UNLOADS = 7, UNLOAD_TASK = 8, EAGER = 9, SAVE = 10,
            SAVE_EVENT = 11, POI_FLUSH = 12, LEVEL_UNLOAD = 13, DROP = 14;
    private static final String[] NAMES = {"main write", "  of it palettes", "main store", "pool fill", "pool record", "light snapshot", "  of it block entities (calls)",
            "ChunkMap.processUnloads (chunk_unload)", "  unload tasks", "  eager saveChunkIfNeeded", "ChunkMap.save", "  of it ChunkDataEvent.Save", "  of it poi flush",
            "  unload task's level.unload", "  toDrop scheduleUnload"};
    private static final int PARTS = NAMES.length;
    private static final LongAdder[] N = new LongAdder[PARTS], BYTES = new LongAdder[PARTS], NS = new LongAdder[PARTS];
    /** the slowest main write (ns) since the last report */
    private static final java.util.concurrent.atomic.AtomicLong MAX_WRITE = new java.util.concurrent.atomic.AtomicLong();

    static {
        for (int i = 0; i < PARTS; i++) {
            N[i] = new LongAdder();
            BYTES[i] = new LongAdder();
            NS[i] = new LongAdder();
        }
    }

    public static long bytes() {
        return MX.getCurrentThreadAllocatedBytes();
    }

    public static void add(int part, long bytes0, long ns0) {
        long ns = System.nanoTime() - ns0;
        N[part].increment();
        BYTES[part].add(bytes() - bytes0);
        NS[part].add(ns);
        if (part == WRITE) {
            MAX_WRITE.accumulateAndGet(ns, Math::max);
            periodic();
        }
    }

    /** one block entity's tag: [saves, ns, bytes, of them on the pool] */
    private static final ConcurrentHashMap<Class<?>, LongAdder[]> TYPES = new ConcurrentHashMap<>();

    public static void type(Class<?> c, long bytes0, long ns0, boolean pool) {
        long ns = System.nanoTime() - ns0, b = bytes() - bytes0;
        LongAdder[] a = TYPES.computeIfAbsent(c, k -> new LongAdder[] {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()});
        a[0].increment();
        a[1].add(ns);
        a[2].add(b);
        if (pool) a[3].increment();
    }

    private static final long EVERY = Long.getLong("lab.save.statsEvery", 30) * 1_000_000_000L;
    private static volatile long nextPrint = System.nanoTime() + EVERY;

    private static void periodic() {
        long now = System.nanoTime();
        if (EVERY <= 0 || now < nextPrint) return;
        nextPrint = now + EVERY;
        System.out.println("[sleep/opt] " + report());
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("save stats:");
        for (int i = 0; i < PARTS; i++) {
            long n = N[i].sum();
            if (n == 0) continue;
            sb.append(String.format(java.util.Locale.ROOT, " | %s %d x %.1f KB %.1f us (total %.1f MB, %.0f ms)", NAMES[i], n,
                    BYTES[i].sum() / 1024.0 / n, NS[i].sum() / 1e3 / n, BYTES[i].sum() / 1048576.0, NS[i].sum() / 1e6));
        }
        long w = N[WRITE].sum();
        if (w > 0) sb.append(String.format(java.util.Locale.ROOT, " | per main write: block entities %.2f ms, rest %.2f ms (max write %.1f ms)",
                NS[BE].sum() / 1e6 / w, (NS[WRITE].sum() - NS[BE].sum() - NS[PALETTES].sum()) / 1e6 / w, MAX_WRITE.getAndSet(0) / 1e6));
        if (EagerSaveScan.walks.get() > 0) sb.append("\n[sleep/opt] ").append(EagerSaveScan.report());
        if (ParallelBeSave.ON) sb.append("\n[sleep/opt] ").append(ParallelBeSave.report());
        if (!TYPES.isEmpty()) {
            java.util.List<java.util.Map.Entry<Class<?>, LongAdder[]>> l = new java.util.ArrayList<>(TYPES.entrySet());
            l.sort((x, y) -> Long.compare(y.getValue()[1].sum(), x.getValue()[1].sum()));
            long all = 0, alln = 0;
            for (var e : l) {
                all += e.getValue()[1].sum();
                alln += e.getValue()[0].sum();
            }
            sb.append(String.format(java.util.Locale.ROOT, "\n[sleep/opt] save stats by block entity class (%d classes, %d tags, %.0f ms):", l.size(), alln, all / 1e6));
            for (int i = 0; i < Math.min(25, l.size()); i++) {
                LongAdder[] a = l.get(i).getValue();
                long n = a[0].sum();
                sb.append(String.format(java.util.Locale.ROOT, "\n  %6.1f%% %7.0f ms %7d x %8.1f us %7.1f KB pool %d  %s", 100.0 * a[1].sum() / Math.max(1, all), a[1].sum() / 1e6, n,
                        a[1].sum() / 1e3 / n, a[2].sum() / 1024.0 / n, a[3].sum(), l.get(i).getKey().getName()));
            }
        }
        return sb.toString();
    }
}
