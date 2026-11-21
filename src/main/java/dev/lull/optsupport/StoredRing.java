package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * StoredRing (lab.opt.StoredRingMixin, notes/85): early proto chunks kept in RAM instead of written at once.
 *
 * <p>Measured during fast generation (Chunky, notes/85): half of all chunk records written are proto chunks at the
 * generation edge saved at structure_starts (or biomes / noise ...) when they unload; most are loaded again within
 * seconds as the generation front comes back (loading marks a proto chunk unsaved, so it is saved again unchanged),
 * and written again - several times - until they finally become FULL. Each of these writes costs its sectors, a whole
 * 8 KB region header and two device syncs.
 *
 * <p>Here a store of a chunk whose status comes before carvers (structure_starts, structure_references, biomes, noise,
 * surface; nothing of a neighbour is in it yet, all of it is regenerated identically from the seed) is encoded into
 * its region record bytes (the same bytes ChunkIo / vanilla write) and kept in a bounded byte ring per IOWorker
 * instead of going to the IOWorker. A later store of the position replaces it (a later status, a FULL chunk, goes to
 * the IOWorker and the held record is dropped: never written). Loads and scans of a held position read its bytes.
 * Held records go to the IOWorker - their bytes handed to ChunkIo's pending entry as they are - when they are older than holdMs, when the
 * ring needs their space, and at every synchronize (flushWorker, save-all flush) and close: the world on disk after a
 * stop is the same as vanilla's. A crash loses only held early proto chunks - regenerated as they were.
 *
 * <p>One byte[] ring (lab.storedring.mb, 16) and fixed slot arrays (lab.storedring.slots, 32768) per chunk IOWorker, all
 * allocated at its first held store. The ring spans every held store of the last holdMs, replaced ones included (the tail
 * only passes them): a 10-minute Chunky soak at 170 chunks/s held ~240 stores/s, live at most 8499 records / 3.4 MB
 * (notes/87); with 8 MB / 16384 slots the oldest live records were already released for space after 90 s. The index maps
 * a position to its newest slot. Every operation under the ring's lock (a release queues the store in the IOWorker's
 * mailbox before the position leaves the index: a load either sees the ring or comes after the store in the mailbox).
 *
 * <p>{@code -Dlab.storedring.verify=true} (or lab.chunkio.verify): every store's tag is hashed; at close every position
 * is read back through vanilla's RegionFileStorage.read and compared, and every load served from the ring is compared
 * with the last stored tag.
 */
public final class StoredRing {
    public static final boolean VERIFY = Boolean.getBoolean("lab.storedring.verify") || ChunkIo.VERIFY;
    static final int CAP = Math.max(1, Integer.getInteger("lab.storedring.mb", 16)) << 20;
    static final int SLOTS = Math.max(1024, Integer.getInteger("lab.storedring.slots", 32768));
    static final long HOLD_NS = Long.getLong("lab.storedring.holdMs", 120_000L) * 1_000_000L;
    static final Set<String> HOLDABLE = Set.of("minecraft:empty", "minecraft:structure_starts", "minecraft:structure_references",
            "minecraft:biomes", "minecraft:noise", "minecraft:surface");

    public static final AtomicLong held = new AtomicLong(), replaced = new AtomicLong(), dropped = new AtomicLong(), released = new AtomicLong(),
            releasedAge = new AtomicLong(), releasedSpace = new AtomicLong(), releasedSync = new AtomicLong(), loadHits = new AtomicLong(),
            scanHits = new AtomicLong(), heldBytes = new AtomicLong(), maxHeldBytes = new AtomicLong(), maxLive = new AtomicLong(), verifyBad = new AtomicLong(),
            encodeFails = new AtomicLong(), releaseAllNs = new AtomicLong();

    /** stores through the IOWorker (bypassing the ring) */
    private final BiFunction<ChunkPos, CompoundTag, CompletableFuture<Void>> through;
    private final String name;
    private byte[] buf;   // allocated at the first held store
    private long head;    // bytes appended (monotonic); a slot's bytes at start % CAP
    // (allocated with buf: a dimension that never holds a record costs nothing)
    private long[] slotPos, slotStart, slotTime;
    private int[] slotLen;
    private CompletableFuture<Void>[] slotFut;
    private boolean[] slotLive;
    private long slotHead, slotTail;   // slots in use: [tail, head)
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private int live;
    private final Map<Long, Map<String, String>> verify = VERIFY ? new ConcurrentHashMap<>() : null;

    public StoredRing(String name, BiFunction<ChunkPos, CompoundTag, CompletableFuture<Void>> through) {
        this.name = name;
        this.through = through;
        index.defaultReturnValue(-1);
    }

    static boolean holdable(CompoundTag tag) {
        return tag != null && HOLDABLE.contains(tag.getString("Status"));
    }

    /** a store: held (returns its future) or null - the caller then stores it through {@link #storeThrough} */
    public CompletableFuture<Void> store(ChunkPos pos, CompoundTag tag) {
        if (verify != null) {
            if (tag == null) verify.remove(pos.toLong());
            else verify.put(pos.toLong(), ChunkIo.canonical(tag));
        }
        if (!holdable(tag)) return null;
        byte[] rec;
        try {
            rec = ChunkIo.encode(tag, RegionFileVersion.VERSION_DEFLATE);   // 1.20.1 always writes deflate
        } catch (IOException | RuntimeException e) {
            encodeFails.incrementAndGet();
            return null;
        }
        if (rec.length > CAP / 4) return null;
        synchronized (this) {
            if (buf == null) allocate();
            long now = System.nanoTime();
            long key = pos.toLong();
            CompletableFuture<Void> fut;
            int old = index.get(key);
            if (old >= 0) {   // a newer early status (or the same, saved again): the held record is replaced, never written
                slotLive[old] = false;
                fut = slotFut[old];
                slotFut[old] = null;
                heldBytes.addAndGet(-slotLen[old]);
                live--;
                replaced.incrementAndGet();
            } else {
                fut = new CompletableFuture<>();
            }
            // room: the record contiguous at head (a record never wraps: the rest of the buffer is skipped)
            long start = head;
            int at = (int) (start % CAP);
            if (at + rec.length > CAP) start += CAP - at;
            makeRoom(start + rec.length, now);
            int s = (int) (slotHead % SLOTS);
            System.arraycopy(rec, 0, buf, (int) (start % CAP), rec.length);
            head = start + rec.length;
            slotPos[s] = key;
            slotStart[s] = start;
            slotLen[s] = rec.length;
            slotTime[s] = now;
            slotFut[s] = fut;
            slotLive[s] = true;
            slotHead++;
            index.put(key, s);
            live++;
            maxHeldBytes.accumulateAndGet(heldBytes.addAndGet(rec.length), Math::max);
            maxLive.accumulateAndGet(live, Math::max);
            held.incrementAndGet();
            releaseOld(now);
            return fut;
        }
    }

    /**
     * a store that is not held goes to the IOWorker (original) under the lock: a held record of the position is dropped
     * (never written) and its future completes with this store's. A load either sees the held record or comes after
     * this store in the IOWorker's mailbox.
     */
    public CompletableFuture<Void> storeThrough(ChunkPos pos, java.util.function.Supplier<CompletableFuture<Void>> original) {
        synchronized (this) {
            CompletableFuture<Void> f = original.get();
            if (live == 0) return f;
            int s = index.remove(pos.toLong());
            if (s < 0) return f;
            CompletableFuture<Void> fut = slotFut[s];
            slotLive[s] = false;
            slotFut[s] = null;
            heldBytes.addAndGet(-slotLen[s]);
            live--;
            dropped.incrementAndGet();
            if (fut != null) f.whenComplete((v, t) -> {
                if (t != null) fut.completeExceptionally(t);
                else fut.complete(null);
            });
            return f;
        }
    }

    @SuppressWarnings("unchecked")
    private void allocate() {
        buf = new byte[CAP];
        slotPos = new long[SLOTS];
        slotStart = new long[SLOTS];
        slotTime = new long[SLOTS];
        slotLen = new int[SLOTS];
        slotFut = new CompletableFuture[SLOTS];
        slotLive = new boolean[SLOTS];
    }

    /** room for bytes up to end and one slot: the oldest slots released (written) or skipped (replaced) */
    private void makeRoom(long end, long now) {
        while (slotHead - slotTail >= SLOTS || (slotHead > slotTail && end - slotStart[(int) (slotTail % SLOTS)] > CAP)) {
            int t = (int) (slotTail % SLOTS);
            if (slotLive[t]) {
                release(t);
                releasedSpace.incrementAndGet();
            }
            slotTail++;
        }
    }

    private void releaseOld(long now) {
        while (slotHead > slotTail) {
            int t = (int) (slotTail % SLOTS);
            if (slotLive[t]) {
                if (now - slotTime[t] < HOLD_NS) return;
                release(t);
                releasedAge.incrementAndGet();
            }
            slotTail++;
        }
    }

    /**
     * under the lock: the record goes to the IOWorker (queued in its mailbox) as it is - ChunkIo's pending entry takes
     * the bytes, no decode and encode again (the ring is on only with ChunkIo) - then leaves the index
     */
    private void release(int s) {
        CompletableFuture<Void> fut = slotFut[s];
        CompletableFuture<Void> f = through.apply(new ChunkPos(slotPos[s]), new ChunkIo.Encoded(copy(s)));
        slotLive[s] = false;
        slotFut[s] = null;
        index.remove(slotPos[s]);
        heldBytes.addAndGet(-slotLen[s]);
        live--;
        released.incrementAndGet();
        if (fut != null) f.whenComplete((v, t) -> {
            if (t != null) fut.completeExceptionally(t);
            else fut.complete(null);
        });
    }

    private byte[] copy(int s) {
        byte[] r = new byte[slotLen[s]];
        System.arraycopy(buf, (int) (slotStart[s] % CAP), r, 0, slotLen[s]);
        return r;
    }

    /** the held record of a position (a copy) or null */
    private byte[] held(ChunkPos pos) {
        synchronized (this) {
            int s = index.get(pos.toLong());
            return s < 0 ? null : copy(s);
        }
    }

    /** a load: the held record decoded, or null - the IOWorker loads it */
    public CompletableFuture<Optional<CompoundTag>> load(ChunkPos pos) {
        byte[] rec = held(pos);
        if (rec == null) return null;
        loadHits.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompoundTag tag = ChunkIo.decode(rec);
                if (verify != null) {
                    Map<String, String> want = verify.get(pos.toLong());
                    if (want != null && !want.equals(ChunkIo.canonical(tag))) {
                        verifyBad.incrementAndGet();
                        System.out.println("[sleep/opt] stored ring verify: load of " + pos + " from the ring differs in " + ChunkIo.diff(want, ChunkIo.canonical(tag)));
                    }
                }
                return Optional.of(tag);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, dev.lull.api.Pools.background());
    }

    public CompletableFuture<Void> scan(ChunkPos pos, StreamTagVisitor visitor) {
        byte[] rec = held(pos);
        if (rec == null) return null;
        scanHits.incrementAndGet();
        return CompletableFuture.runAsync(() -> {
            try {
                ChunkIo.scan(rec, visitor);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, dev.lull.api.Pools.background());
    }

    /** every held record to the IOWorker (synchronize, close) */
    public void releaseAll() {
        long t = System.nanoTime();
        try {
            releaseAll0();
        } finally {
            releaseAllNs.addAndGet(System.nanoTime() - t);
        }
    }

    private void releaseAll0() {
        synchronized (this) {
            while (slotHead > slotTail) {
                int t = (int) (slotTail % SLOTS);
                if (slotLive[t]) {
                    release(t);
                    releasedSync.incrementAndGet();
                }
                slotTail++;
            }
        }
    }

    /** at close, after the IOWorker wrote everything: every stored position read back through the storage */
    public void verifyAll(java.util.function.Function<ChunkPos, CompoundTag> read) {
        if (verify == null) return;
        int ok = 0, bad = 0, missing = 0;
        for (Map.Entry<Long, Map<String, String>> v : verify.entrySet()) {
            CompoundTag back;
            try {
                back = read.apply(new ChunkPos(v.getKey()));
            } catch (RuntimeException e) {
                back = null;
            }
            if (back == null) missing++;
            else if (v.getValue().equals(ChunkIo.canonical(back))) ok++;
            else if (bad++ < 5) System.out.println("[sleep/opt] stored ring verify: " + new ChunkPos(v.getKey()) + " read back differs in " + ChunkIo.diff(v.getValue(), ChunkIo.canonical(back)));
        }
        System.out.println("[sleep/opt] stored ring verify (" + name + "): " + ok + " equal, " + bad + " differ, " + missing + " missing of " + verify.size()
                + "; loads from the ring that differed " + verifyBad.get());
    }

    public static String report() {
        return String.format("stored ring: held %d (replaced %d, dropped by a later status %d), released %d (age %d, space %d, sync/close %d; hand-overs at sync/close %.1f ms), loads from the ring %d, scans %d, max live %d, held now %.1f MB (peak %.1f MB), encode failures %d",
                held.get(), replaced.get(), dropped.get(), released.get(), releasedAge.get(), releasedSpace.get(), releasedSync.get(), releaseAllNs.get() / 1e6, loadHits.get(), scanHits.get(),
                maxLive.get(), heldBytes.get() / 1048576.0, maxHeldBytes.get() / 1048576.0, encodeFails.get());
    }
}
