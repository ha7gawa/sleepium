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
 * <p>{@code -Dlab.chunkio.writeThrough=true} (1.3.5's default, defaults.txt): the region file keeps vanilla's DSYNC;
 * per batch the records (contiguous ones in one write), each acknowledged by the device, then the 8 KB header, then the
 * frees - no forces at all. Exactly vanilla's guarantee and world size. Measured in game (notes/87): host F: NVMe disk
 * active 42 -> 11 %, .92 SATA 13 -> 10 %, Pi 4 USB stick (Linux) equal (19 %, fewer writes). A probe per volume (11-21)
 * chose write-through on every drive tested, so it was dropped: write-through everywhere, false = 1.3.4's forces.
 * {@code -Dlab.chunkio.coalesce=false}: one write per record (with write-through per-record device writes: the Pi's
 * stick 40 % busy and a 9 s stop instead of 19 % and 2 s).
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
 * <p>Tried and dropped (notes/86): header deltas (a 4-byte write still costs the device a page and the flush), growth
 * in steps of zeros (twice the bytes).
 */
public final class ChunkIoWriter {
    private ChunkIoWriter() {}

    /**
     * {@code -Dlab.chunkio.syncCadence=true} (the settings screen's switch, off by default): cadence 500 ms and hold cap
     * 64 sectors - the fewest device writes (SD cards, eMMC, USB HDDs); a hard crash or power loss may revert the last
     * ~1 s of chunk saves to their previous stored copy (never a broken file); world +2 %. Each value can still be given.
     */
    public static final boolean SYNC_CADENCE = Boolean.getBoolean("lab.chunkio.syncCadence");
    public static final int CADENCE_MS = Integer.getInteger("lab.chunkio.cadence", SYNC_CADENCE ? 500 : 0);
    public static final long CADENCE_NS = CADENCE_MS * 1_000_000L;

    /** cadence: a round early once this many sectors wait to be freed in a file (bounds the file growth they cause; 0 = off) */
    public static final int HOLD_CAP = Integer.getInteger("lab.chunkio.holdCap", SYNC_CADENCE ? 64 : 0);
    /** contiguous records of a batch in one write (sorted by start sector) */
    public static final boolean COALESCE = !"false".equals(System.getProperty("lab.chunkio.coalesce"));
    /** true (defaults.txt): write-through batches; false: 1.3.4's forces ("auto", the dropped probe's value, = true) */
    public static final String WRITE_THROUGH_FLAG = System.getProperty("lab.chunkio.writeThrough", "false");
    public static final boolean WRITE_THROUGH_ON = "true".equals(WRITE_THROUGH_FLAG) || "auto".equals(WRITE_THROUGH_FLAG);

    /** how a region file opened for sync writes is kept durable */
    public static final byte FORCE = 1, WRITE_THROUGH = 2, CADENCE = 3;

    /** the mode for a region file opened for sync writes */
    public static byte mode() {
        if (CADENCE_MS > 0) return CADENCE;
        return WRITE_THROUGH_ON ? WRITE_THROUGH : FORCE;
    }

    public static final AtomicLong forces = new AtomicLong(), forceNs = new AtomicLong(), forceMaxNs = new AtomicLong(),
            rounds = new AtomicLong(), headerWrites = new AtomicLong(), headerBytes = new AtomicLong();

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
        return String.format("writer: cadence %d ms" + (HOLD_CAP > 0 ? " (hold cap " + HOLD_CAP + ")" : "") + ", write-through " + WRITE_THROUGH_ON + (COALESCE ? ", coalesced" : "") + ": forces %d (%.2f ms avg, max %.1f), rounds %d, header writes %d",
                CADENCE_MS, f, f == 0 ? 0 : forceNs.get() / 1e6 / f, forceMaxNs.get() / 1e6, rounds.get(), headerWrites.get());
    }
}
