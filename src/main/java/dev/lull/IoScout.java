package dev.lull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measurement only (-Dsleep.ioScout=true): how the IOWorker mailboxes drain their write queues. Per storage type
 * (chunk / entities / poi): stores posted (producer), chunks written (consumer), time per write split into the whole
 * runStore (NBT + compression + disk), RegionFile.write (disk) and its header write, the pendingWrites size seen by the
 * writer. Logged every 5 s as "[sleep/io]".
 *
 * <p>Also (notes/85): header writes (count, ms each: the whole writeHeader - with ChunkIo the force before, the 8 KB
 * write, the force after - and the 8 KB write alone), sectors allocated (bytes written, rounded to 4 KB sectors) and
 * the region files' growth, per storage type, every 5 s and as totals every 15 s ("[sleep/io] totals").
 *
 * <p>{@code -Dsleep.ioScout.pos=true} adds per position (dimension, type): how many times each is stored, the chunk
 * status of each store and the status it replaced, the time between stores of a position, loads and disk reads of
 * positions stored earlier in this session (and how long after the store), who called ChunkMap.save (with its status
 * and result) and who loaded chunks - every 15 s cumulative ("[sleep/io] pos").
 */
public final class IoScout {
    public static final boolean ON = Boolean.getBoolean("sleep.ioScout");
    public static final boolean POS = ON && Boolean.getBoolean("sleep.ioScout.pos");
    private static final long T0 = System.nanoTime();

    public static final class Kind {
        final LongAdder stores = new LongAdder(), loads = new LongAdder(), reads = new LongAdder(), readNs = new LongAdder();
        final LongAdder writes = new LongAdder(), writeNs = new LongAdder(), diskNs = new LongAdder(), headerNs = new LongAdder();
        final LongAdder headers = new LongAdder(), headerWriteNs = new LongAdder(), sectors = new LongAdder(), growth = new LongAdder(), nulls = new LongAdder();
        final AtomicLong writeMax = new AtomicLong(), diskMax = new AtomicLong(), headerMax = new AtomicLong();
        // totals since the start (not reset by the 5 s window)
        final LongAdder tStores = new LongAdder(), tNulls = new LongAdder(), tWrites = new LongAdder(), tHeaders = new LongAdder(), tHeaderNs = new LongAdder(),
                tHeaderWriteNs = new LongAdder(), tSectors = new LongAdder(), tGrowth = new LongAdder(), tReads = new LongAdder(), tLoads = new LongAdder(),
                tBusyNs = new LongAdder();
        volatile int pending, pendingMax, tPendingMax;
    }

    private static final Map<String, Kind> KINDS = new ConcurrentHashMap<>();
    /** the storage type of the IOWorker whose writer runs on this thread (RegionFile does not know its worker) */
    private static final ThreadLocal<Kind> CURRENT = new ThreadLocal<>();

    private IoScout() {}

    public static Kind kind(String type) {
        return KINDS.computeIfAbsent(type, t -> new Kind());
    }

    public static void stored(String type) { kind(type).stores.increment(); kind(type).tStores.increment(); }
    public static void loaded(String type) { kind(type).loads.increment(); kind(type).tLoads.increment(); }

    public static void read(String type, long ns) {
        Kind k = kind(type);
        k.reads.increment();
        k.readNs.add(ns);
        k.tReads.increment();
        k.tBusyNs.add(ns);
    }

    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

    public static void beginWrite(String type) {
        CURRENT.set(kind(type));
        START.get()[0] = System.nanoTime();
    }

    public static void endWrite(int pending) {
        Kind k = CURRENT.get();
        CURRENT.remove();
        if (k == null) return;
        long ns = System.nanoTime() - START.get()[0];
        k.writes.increment();
        k.writeNs.add(ns);
        k.tWrites.increment();
        k.tBusyNs.add(ns);
        k.writeMax.accumulateAndGet(ns, Math::max);
        pending(k, pending);
    }

    private static void pending(Kind k, int pending) {
        k.pending = pending;
        if (pending > k.pendingMax) k.pendingMax = pending;
        if (pending > k.tPendingMax) k.tPendingMax = pending;
    }

    /** the IOWorker's storePendingChunk runs on this thread (ChunkIo's writer: no runStore to name the type) */
    public static void writerBegin(String type) {
        CURRENT.set(kind(type));
    }

    public static void writerEnd() {
        CURRENT.remove();
    }

    /** ChunkIo's writer: one batch of n chunks */
    public static void batch(String type, int n, long ns, int pending) {
        Kind k = kind(type);
        k.writes.add(n);
        k.writeNs.add(ns);
        k.tWrites.add(n);
        k.tBusyNs.add(ns);
        k.writeMax.accumulateAndGet(ns, Math::max);
        pending(k, pending);
    }

    public static void disk(long ns) {
        Kind k = CURRENT.get();
        if (k == null) return;
        k.diskNs.add(ns);
        k.diskMax.accumulateAndGet(ns, Math::max);
    }

    /** a region file's writeHeader: whole (with ChunkIo: force, 8 KB write, force) and the 8 KB write alone */
    public static void header(String type, long ns, long writeNs) {
        Kind k = kind(type);
        k.headerNs.add(ns);
        k.headers.increment();
        k.headerWriteNs.add(writeNs);
        k.headerMax.accumulateAndGet(ns, Math::max);
        k.tHeaders.increment();
        k.tHeaderNs.add(ns);
        k.tHeaderWriteNs.add(writeNs);
    }

    private static final ThreadLocal<long[]> HEADER_WRITE = ThreadLocal.withInitial(() -> new long[1]);

    public static void headerWrite(long ns) {
        HEADER_WRITE.get()[0] += ns;
    }

    public static long takeHeaderWrite() {
        long[] a = HEADER_WRITE.get();
        long v = a[0];
        a[0] = 0;
        return v;
    }

    /** RegionBitmap.allocate on a writer thread: sectors taken, of them past the file's previous end */
    public static void allocated(int sectors, int growth) {
        Kind k = CURRENT.get();
        if (k == null) return;
        k.sectors.add(sectors);
        k.tSectors.add(sectors);
        if (growth > 0) {
            k.growth.add(growth);
            k.tGrowth.add(growth);
        }
    }

    // ---- per position (sleep.ioScout.pos) ----

    private static int nowMs() {
        return (int) ((System.nanoTime() - T0) / 1_000_000L);
    }

    private static final String[] GAPS = {"<1s", "1-5s", "5-30s", "30-120s", ">120s"};

    private static String gap(int ms) {
        return ms < 1000 ? GAPS[0] : ms < 5000 ? GAPS[1] : ms < 30000 ? GAPS[2] : ms < 120000 ? GAPS[3] : GAPS[4];
    }

    /** one (dimension, storage type): per position {stores, last status, last store ms, loads, loads after a store, disk reads, disk reads after a store, null stores} */
    private static final class Track {
        final HashMap<Long, int[]> pos = new HashMap<>();
        final HashMap<String, long[]> counts = new HashMap<>();
        long stores, loads, loadsStored, reads, readsStored, readsPresent;

        void count(String k) {
            counts.computeIfAbsent(k, x -> new long[1])[0]++;
        }
    }

    private static final Map<String, Track> TRACKS = new ConcurrentHashMap<>();
    private static final List<String> STATUS = new ArrayList<>();
    private static final HashMap<String, long[]> SAVES = new HashMap<>(), LOADERS = new HashMap<>(), STORERS = new HashMap<>();

    private static synchronized int statusIndex(String s) {
        int i = STATUS.indexOf(s);
        if (i < 0) {
            STATUS.add(s);
            i = STATUS.size() - 1;
        }
        return i;
    }

    private static Track track(String dim, String type) {
        return TRACKS.computeIfAbsent(dim + "/" + type, k -> new Track());
    }

    /** a store posted to an IOWorker: status = the chunk tag's Status ("-" for entities / poi, "null" for a clear) */
    public static void storePos(String dim, String type, long pos, String status) {
        int si = statusIndex(status);
        int now = nowMs();
        Track t = track(dim, type);
        synchronized (t) {
            t.stores++;
            int[] p = t.pos.computeIfAbsent(pos, k -> new int[] {0, -1, 0, 0, 0, 0, 0, 0});
            if (p[0] > 0) {
                t.count("restore " + statusName(p[1]) + " > " + status);
                t.count("restore gap " + gap(now - p[2]));
            }
            p[0]++;
            p[1] = si;
            p[2] = now;
            if ("null".equals(status)) p[7]++;
        }
    }

    private static synchronized String statusName(int i) {
        return i < 0 ? "none" : STATUS.get(i);
    }

    public static void loadPos(String dim, String type, long pos) {
        int now = nowMs();
        Track t = track(dim, type);
        synchronized (t) {
            t.loads++;
            int[] p = t.pos.get(pos);
            if (p == null) return;
            p[3]++;
            if (p[0] > 0) {
                p[4]++;
                t.loadsStored++;
                t.count("load after store " + gap(now - p[2]) + " (was " + statusName(p[1]) + ")");
            }
        }
    }

    public static void readPos(String dim, String type, long pos, boolean present) {
        int now = nowMs();
        Track t = track(dim, type);
        synchronized (t) {
            t.reads++;
            if (present) t.readsPresent++;
            int[] p = t.pos.get(pos);
            if (p == null || p[0] == 0) return;
            p[5]++;
            p[6]++;
            t.readsStored++;
            t.count("disk read after store " + gap(now - p[2]));
        }
    }

    private static void inc(HashMap<String, long[]> m, String k) {
        synchronized (m) {
            m.computeIfAbsent(k, x -> new long[1])[0]++;
        }
    }

    public static void save(String caller, String status, String cls, boolean result) {
        inc(SAVES, caller + " | " + cls + " " + status + " -> " + result);
    }

    public static void loader(String caller) {
        inc(LOADERS, caller);
    }

    public static void storer(String type, String caller) {
        inc(STORERS, type + " <- " + caller);
    }

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final java.util.Set<String> SKIP = java.util.Set.of("net.minecraft.world.level.chunk.storage.IOWorker",
            "net.minecraft.world.level.chunk.storage.SimpleRegionStorage", "net.minecraft.world.level.chunk.storage.ChunkStorage");

    /** the first two frames outside storage, this scout, mixin handlers, java.* and the method itself (skipName) */
    public static String caller(String skipName) {
        return WALKER.walk(s -> {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackWalker.StackFrame f : (Iterable<StackWalker.StackFrame>) s::iterator) {
                String c = f.getClassName(), m = f.getMethodName();
                if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("dev.lull.") || c.startsWith("com.llamalad7.") || c.startsWith("it.unimi."))
                    continue;
                if (SKIP.contains(c) || m.equals(skipName) || m.contains("sleep$") || m.startsWith("wrapMethod$") || m.startsWith("handler$")) continue;
                if (n++ > 0) sb.append(" < ");
                sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(m);
                if (n == 2) break;
            }
            return sb.toString();
        });
    }

    private static String top(HashMap<String, long[]> m, int max, String prefix) {
        List<Map.Entry<String, long[]>> l;
        synchronized (m) {
            l = new ArrayList<>(m.entrySet());
        }
        l.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, l.size()); i++) sb.append("\n   ").append(prefix).append(l.get(i).getValue()[0]).append("  ").append(l.get(i).getKey());
        return sb.toString();
    }

    private static String posReport() {
        StringBuilder sb = new StringBuilder("[sleep/io] pos (cumulative, ").append(nowMs() / 1000).append(" s):");
        TRACKS.forEach((key, t) -> {
            synchronized (t) {
                long[] hist = new long[6];
                long nulls = 0;
                for (int[] p : t.pos.values()) {
                    hist[Math.min(5, p[0])]++;
                    nulls += p[7];
                }
                long stored = t.pos.values().stream().filter(p -> p[0] > 0).count();
                sb.append(String.format("\n  %s: stores %d at %d positions (%.2f per position; 1x %d, 2x %d, 3x %d, 4x %d, 5+x %d), null stores %d; loads %d, of positions stored before %d; disk reads %d (present %d), of positions stored before %d",
                        key, t.stores, stored, stored == 0 ? 0 : t.stores / (double) stored, hist[1], hist[2], hist[3], hist[4], hist[5], nulls, t.loads, t.loadsStored, t.reads, t.readsPresent, t.readsStored));
                sb.append(top(t.counts, 24, "  "));
            }
        });
        sb.append("\n  ChunkMap.save callers:").append(top(SAVES, 16, "  "));
        sb.append("\n  chunk loads by caller:").append(top(LOADERS, 10, "  "));
        sb.append("\n  entities/poi stores by caller:").append(top(STORERS, 10, "  "));
        return sb.toString();
    }

    private static String totals() {
        StringBuilder sb = new StringBuilder("[sleep/io] totals ").append(nowMs() / 1000).append(" s:");
        KINDS.forEach((type, k) -> {
            long h = k.tHeaders.sum();
            sb.append(String.format(" | %s: stores %d (null %d), written %d, loads %d (from disk %d), headers %d (%.2f ms each, 8 KB write %.3f ms), sectors %.1f MB, growth %.1f MB, header bytes %.1f MB, busy %.1f s, pending max %d",
                    type, k.tStores.sum(), k.tNulls.sum(), k.tWrites.sum(), k.tLoads.sum(), k.tReads.sum(), h, h == 0 ? 0 : k.tHeaderNs.sum() / 1e6 / h,
                    h == 0 ? 0 : k.tHeaderWriteNs.sum() / 1e6 / h, k.tSectors.sum() * 4096 / 1048576.0, k.tGrowth.sum() * 4096 / 1048576.0, h * 8192 / 1048576.0,
                    k.tBusyNs.sum() / 1e9, k.tPendingMax));
        });
        return sb.toString();
    }

    public static void nullStore(String type) {
        kind(type).nulls.increment();
        kind(type).tNulls.increment();
    }

    static {
        if (ON) {
            Thread t = new Thread(() -> {
                int tick = 0;
                while (true) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    StringBuilder sb = new StringBuilder("[sleep/io] 5 s:");
                    KINDS.forEach((type, k) -> {
                        long st = k.stores.sumThenReset(), ld = k.loads.sumThenReset(), w = k.writes.sumThenReset(), rd = k.reads.sumThenReset(), rn = k.readNs.sumThenReset();
                        long wn = k.writeNs.sumThenReset(), dn = k.diskNs.sumThenReset(), hn = k.headerNs.sumThenReset();
                        long hc = k.headers.sumThenReset(), hw = k.headerWriteNs.sumThenReset(), sec = k.sectors.sumThenReset(), gr = k.growth.sumThenReset(), nu = k.nulls.sumThenReset();
                        long wm = k.writeMax.getAndSet(0), dm = k.diskMax.getAndSet(0), hm = k.headerMax.getAndSet(0);
                        int pm = k.pendingMax;
                        k.pendingMax = 0;
                        if (st == 0 && w == 0 && ld == 0 && k.pending == 0) return;
                        sb.append(String.format(" | %s: loads %d (from disk %d, %.2f ms each), stores %d (null %d), written %d (busy reads+writes %.0f%%), per write %.2f ms (max %.1f): disk %.2f ms (max %.1f); headers %d (%.2f ms each, max %.1f; 8 KB write %.3f ms), sectors %d KB, growth %d KB; pending %d (max %d)",
                                type, ld, rd, rd == 0 ? 0 : rn / 1e6 / rd, st, nu, w, (wn + rn) / 5e7, w == 0 ? 0 : wn / 1e6 / w, wm / 1e6, w == 0 ? 0 : dn / 1e6 / w, dm / 1e6,
                                hc, hc == 0 ? 0 : hn / 1e6 / hc, hm / 1e6, hc == 0 ? 0 : hw / 1e6 / hc, sec * 4, gr * 4, k.pending, pm));
                    });
                    SleepMod.LOGGER.info(sb.toString());
                    if (++tick % 3 == 0) {
                        SleepMod.LOGGER.info(totals());
                        if (POS) SleepMod.LOGGER.info(posReport());
                    }
                }
            }, "sleep-io-scout");
            t.setDaemon(true);
            t.start();
        }
    }

    /** the last totals at the IOWorkers' close (the stop's save included) */
    public static void closing() {
        SleepMod.LOGGER.info(totals());
        if (POS) SleepMod.LOGGER.info(posReport());
    }
}
