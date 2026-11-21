package dev.lull.optsupport;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The writer side of ChunkIo (notes/86): how region files are made durable. Same .mca format, chunk bytes vanilla's.
 *
 * <p>1.3.3 (ChunkIo's group commit): per batch and region file the records, force, the whole 8 KB header, force. On
 * Windows a force is FlushFileBuffers: the file's data and NTFS metadata, then a flush of the device's whole write cache
 * (2-4 ms on the host's KC3000, ~2 ms on a SATA SSD); ~150 forces/s while generating kept the world's NVMe busy.
 *
 * <p>{@code -Dlab.chunkio.cadence=MS} (0 = off): when a region file is opened for sync writes, it is forced at most
 * once per MS (a "round"), pipelined: a round's force makes the records written since the last round AND the last
 * round's header durable; then the header is written (it only points at records that are now on the device) and stays
 * in the OS cache until the next round's force. Sectors a header stops pointing at (and older .mcc files) are given
 * back only after the next force, when that header is durable (a crash in between still finds the old copies). Rounds
 * come from the writes themselves and, for an idle file, from a timer that posts a task to the IOWorker's own mailbox.
 * flush() (save-all flush, IOWorker.synchronize(true)) and close() (server stop, the region cache's eviction) end fully
 * durable: force, header, force. vanilla's own header writes (clear, oversized chunks) are forced on both sides and
 * release everything pending.
 *
 * <p>{@code -Dlab.chunkio.headerDelta=true}: only the changed 4-byte header entries (offsets, timestamps), contiguous runs
 * in one write each, instead of the whole 8 KB. {@code -Dlab.chunkio.grow=KB}: a region file grows in steps of KB of
 * zeros (vanilla reads past-the-end free sectors fine: only an entry's start sector is checked against the size); the
 * unused tail is cut at close.
 */
public final class ChunkIoWriter {
    private ChunkIoWriter() {}

    public static final int CADENCE_MS = Integer.getInteger("lab.chunkio.cadence", 0);
    public static final long CADENCE_NS = CADENCE_MS * 1_000_000L;
    public static final boolean HEADER_DELTA = Boolean.getBoolean("lab.chunkio.headerDelta");
    public static final int GROW_KB = Integer.getInteger("lab.chunkio.grow", 0);

    public static final boolean COALESCE = Boolean.getBoolean("lab.chunkio.coalesce");
    /** false: 1.3.3's forces; true: write-through batches; auto: a probe per volume picks the cheaper of the two */
    public static final String WRITE_THROUGH_FLAG = System.getProperty("lab.chunkio.writeThrough", "false");

    /** how a region file opened for sync writes is kept durable */
    public static final byte FORCE = 1, WRITE_THROUGH = 2, CADENCE = 3;

    private static final java.util.Map<Object, Byte> PROBED = new java.util.concurrent.ConcurrentHashMap<>();

    /** the mode for a region file in this folder, opened for sync writes */
    public static byte mode(java.nio.file.Path dir) {
        if (CADENCE_MS > 0) return CADENCE;
        if ("true".equals(WRITE_THROUGH_FLAG)) return WRITE_THROUGH;
        if (!"auto".equals(WRITE_THROUGH_FLAG)) return FORCE;
        Object key;
        try {
            key = java.nio.file.Files.getFileStore(dir).name() + "|" + dir.getRoot();
        } catch (Exception e) {
            key = String.valueOf(dir.getRoot());
        }
        return PROBED.computeIfAbsent(key, k -> probe(dir));
    }

    /**
     * Write-through or forces, measured on the folder's volume (~10-50 ms once): a batch of 3 records (12 KB, appended)
     * and the 8 KB header, either both written through (DSYNC; the records acknowledged before the header is issued)
     * or written plainly with a force after each. The median of 5 each, interleaved.
     */
    static byte probe(java.nio.file.Path dir) {
        java.nio.file.Path p = dir.resolve("sleepium-io-probe.tmp");
        long[] wt = new long[5], fo = new long[5];
        java.nio.ByteBuffer rec = java.nio.ByteBuffer.allocate(12288), hdr = java.nio.ByteBuffer.allocate(8192);
        try {
            java.nio.file.Files.createDirectories(dir);
            try (java.nio.channels.FileChannel d = java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.DSYNC);
                 java.nio.channels.FileChannel f = java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
                long off = 8192;
                for (int r = 0; r < 6; r++) {   // round 0: warm-up
                    long t = System.nanoTime();
                    d.write(rec.clear(), off);
                    d.write(hdr.clear(), 0);
                    long a = System.nanoTime() - t;
                    off += 12288;
                    t = System.nanoTime();
                    f.write(rec.clear(), off);
                    f.force(true);
                    f.write(hdr.clear(), 0);
                    f.force(true);
                    long b = System.nanoTime() - t;
                    off += 12288;
                    if (r > 0) {
                        wt[r - 1] = a;
                        fo[r - 1] = b;
                    }
                }
            }
            java.util.Arrays.sort(wt);
            java.util.Arrays.sort(fo);
            byte m = wt[2] <= fo[2] ? WRITE_THROUGH : FORCE;
            System.out.println(String.format("[sleep/opt] chunk io: %s on %s - write-through batch %.2f ms, force batch %.2f ms (medians of 5)",
                    m == WRITE_THROUGH ? "write-through" : "forces", dir, wt[2] / 1e6, fo[2] / 1e6));
            return m;
        } catch (Exception e) {
            System.out.println("[sleep/opt] chunk io: probe in " + dir + " failed (" + e + "), forces");
            return FORCE;
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(p);
            } catch (Exception ignored) {
                // a leftover probe file is harmless (not a region file name)
            }
        }
    }

    public static final AtomicLong forces = new AtomicLong(), forceNs = new AtomicLong(), forceMaxNs = new AtomicLong(),
            rounds = new AtomicLong(), headerWrites = new AtomicLong(), headerBytes = new AtomicLong(), grows = new AtomicLong(),
            growBytes = new AtomicLong(), trimmed = new AtomicLong();

    /** an IOWorker with region files that are not fully durable: the timer posts it a commit task */
    public interface Worker {
        /** any thread: posts the commit task to the worker's mailbox when something is pending */
        void sleep$commitDue();
    }

    private static final Set<Worker> WORKERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean started;

    public static void register(Worker w) {
        if (CADENCE_MS <= 0) return;
        WORKERS.add(w);
        if (!started) start();
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            long sleep = Math.max(20, CADENCE_MS / 4);
            while (true) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { return; }
                Worker[] ws;
                synchronized (WORKERS) {
                    ws = WORKERS.toArray(new Worker[0]);
                }
                for (Worker w : ws) w.sleep$commitDue();
            }
        }, "sleep-chunkio-commit");
        t.setDaemon(true);
        t.start();
    }

    public static void noteForce(long ns) {
        forces.incrementAndGet();
        forceNs.addAndGet(ns);
        forceMaxNs.accumulateAndGet(ns, Math::max);
    }

    public static String report() {
        long f = forces.get();
        return String.format("writer: cadence %d ms, write-through " + WRITE_THROUGH_FLAG + (COALESCE ? ", coalesced" : "") + ", header %s, grow %d KB: forces %d (%.2f ms avg, max %.1f), rounds %d, header writes %d (%.0f B avg), grows %d (%.1f MB), trimmed %.1f MB",
                CADENCE_MS, HEADER_DELTA ? "delta" : "full", GROW_KB, f, f == 0 ? 0 : forceNs.get() / 1e6 / f, forceMaxNs.get() / 1e6, rounds.get(),
                headerWrites.get(), headerWrites.get() == 0 ? 0 : headerBytes.get() / (double) headerWrites.get(), grows.get(), growBytes.get() / 1048576.0,
                trimmed.get() / 1048576.0);
    }
}
