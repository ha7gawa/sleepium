package dev.lull.optsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Chunk storage without blocking (lab.opt.ChunkIo, every IOWorker: chunks, entities, POI; notes/38).
 *
 * <p>Vanilla keeps a stored chunk's whole NBT tree in IOWorker.pendingWrites until its turn on the IOWorker's single
 * thread, which then encodes and deflates it and writes it with two device-synced writes (the chunk's sectors and the
 * whole 8 KB header; region files are opened with DSYNC when sync writes are on, the default on Windows): one chunk
 * per mailbox task, ~1-2 ms of waiting for the device each, the trees held all that time.
 *
 * <p>Here: right after store() the tag is encoded and compressed on the shared pool into exactly the bytes vanilla
 * writes (the same NbtIo.write through the same RegionFileVersion stream, the region chunk header in front), and the
 * IOWorker's entry swaps its tree for those bytes (~3 KB instead of ~21 KB of NBT in thousands of objects). The
 * IOWorker's thread then writes every ready entry at once, grouped by region file: all sectors without syncing, one
 * force, the header once, one force, and only then the old sectors are freed (a crash leaves the header pointing at
 * data already on the device, as with DSYNC). Loads and scans of a pending position read its bytes (vanilla: a copy of
 * the tree). The pool never touches the queue: an encode's result is handed to the IOWorker's own mailbox, which
 * keeps it only if the entry still holds that very tag (a newer store replaced it otherwise).
 *
 * <p>{@code -Dlab.chunkio.verify=true}: every stored tag's raw NBT is hashed, and at close every position is read back
 * through vanilla's RegionFileStorage.read and compared. {@code -Dlab.chunkio.batch=N}: chunks per write (256).
 */
public final class ChunkIo {
    private ChunkIo() {}

    public static final boolean VERIFY = Boolean.getBoolean("lab.chunkio.verify");
    public static final int BATCH = Math.max(1, Integer.getInteger("lab.chunkio.batch", 256));
    /** a pending entry's tag once its bytes are made (the tree is let go); never written or read as a tag */
    public static final CompoundTag DROPPED = new CompoundTag();

    /**
     * A store whose region record is already made (StoredRing's release: the held bytes go to the IOWorker as they are,
     * no decode and encode again). Stored through IOWorker.store like a tag, the pending entry holds it until the writer
     * takes its bytes; loads and scans of the entry read the bytes.
     *
     * <p>Fail-safe (issue #18): if anything outside ChunkIoWorkerMixin serializes, copies, or visits this carrier as a
     * plain CompoundTag, decode {@link #record} instead of emitting an empty root compound.
     */
    public static final class Encoded extends CompoundTag {
        public final byte[] record;

        public Encoded(byte[] record) {
            this.record = record;
        }

        private CompoundTag decodeOrThrow() {
            try {
                return decode(record);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("ChunkIo.Encoded failed to decode region record", e);
            }
        }

        @Override
        public void write(java.io.DataOutput output) throws IOException {
            decode(record).write(output);
        }

        @Override
        public CompoundTag copy() {
            return decodeOrThrow();
        }

        @Override
        public StreamTagVisitor.ValueResult accept(StreamTagVisitor visitor) {
            return decodeOrThrow().accept(visitor);
        }

        @Override
        public void accept(net.minecraft.nbt.TagVisitor visitor) {
            decodeOrThrow().accept(visitor);
        }
    }

    /** the record of a pending entry's data, if it is one already made: DROPPED (the entry's bytes) or an Encoded store */
    public static byte[] record(Entry e, CompoundTag data) {
        if (data == DROPPED) return e.sleep$bytes();
        return data instanceof Encoded enc ? enc.record : null;
    }

    public static final AtomicLong encodedPool = new AtomicLong(), encodedInline = new AtomicLong(), stale = new AtomicLong(),
            batches = new AtomicLong(), written = new AtomicLong(), cleared = new AtomicLong(), maxBatch = new AtomicLong(),
            pendingReads = new AtomicLong();

    /** IOWorker$PendingStore, through ChunkIoPendingMixin. Touched on the IOWorker's thread only. */
    public interface Entry {
        CompoundTag sleep$data();

        void sleep$setData(CompoundTag data);

        byte[] sleep$bytes();

        void sleep$setBytes(byte[] bytes);

        java.util.concurrent.CompletableFuture<Void> sleep$result();
    }

    /** RegionFile, through ChunkIoRegionMixin. */
    public interface Region {
        int sleep$versionId();

        /** writes n chunks (bytes as {@link #encode} makes them): sectors, one sync, the header, one sync, then the frees */
        void sleep$writeBatch(ChunkPos[] pos, byte[][] data, int n) throws IOException;

        /** ChunkIoWriter's cadence: a round if one is due; true while something is still not durable */
        boolean sleep$commitDue(long now) throws IOException;
    }

    /** RegionFileStorage, through ChunkIoStorageMixin. */
    public interface Storage {
        RegionFile sleep$region(ChunkPos pos) throws IOException;

        /** the open region files (the storage's cache) */
        Iterable<RegionFile> sleep$regions();
    }

    /** a region file's chunk record: 4 bytes length, 1 byte version, the compressed NBT — RegionFile.ChunkBuffer's bytes */
    public static byte[] encode(CompoundTag tag, RegionFileVersion version) throws IOException {
        if (!SaveStats.ON) return encode0(tag, version);
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            return encode0(tag, version);
        } finally {
            SaveStats.add(SaveStats.RECORD, b, t);
        }
    }

    private static byte[] encode0(CompoundTag tag, RegionFileVersion version) throws IOException {
        if (SaveStreams.ON) {
            byte[] r = SaveStreams.encode(tag, version);
            if (r != null) return r;
        }
        return encodeVanilla(tag, version);
    }

    static byte[] encodeVanilla(CompoundTag tag, RegionFileVersion version) throws IOException {
        Buffer b = new Buffer(version.getId());
        try (DataOutputStream out = new DataOutputStream(version.wrap(b))) {
            NbtIo.write(tag, out);
        }
        byte[] r = b.toByteArray();
        ByteBuffer.wrap(r).putInt(0, r.length - 5 + 1);
        return r;
    }

    public static CompoundTag decode(byte[] record) throws IOException {
        try (DataInputStream in = stream(record)) {
            return NbtIo.read(in);
        }
    }

    public static void scan(byte[] record, StreamTagVisitor visitor) throws IOException {
        try (DataInputStream in = stream(record)) {
            NbtIo.parse(in, visitor, NbtAccounter.unlimitedHeap());
        }
    }

    private static DataInputStream stream(byte[] record) throws IOException {
        RegionFileVersion v = RegionFileVersion.fromId(record[4]);
        if (v == null) throw new IOException("unknown region chunk version " + record[4]);
        return new DataInputStream(v.wrap(new ByteArrayInputStream(record, 5, record.length - 5)));
    }

    /**
     * For verification: a digest of every top-level entry, compounds' keys sorted at every depth (a CompoundTag is a
     * HashMap: the same content may iterate, and so serialize, in another order after a read)
     */
    public static java.util.Map<String, String> canonical(CompoundTag tag) {
        java.util.Map<String, String> m = new java.util.TreeMap<>();
        try {
            for (String k : tag.getAllKeys()) {
                java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
                ByteArrayOutputStream b = new ByteArrayOutputStream(4096);
                canonical(tag.get(k), new DataOutputStream(b));
                m.put(k, java.util.HexFormat.of().formatHex(d.digest(b.toByteArray()), 0, 8));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    private static void canonical(net.minecraft.nbt.Tag t, DataOutputStream out) throws IOException {
        out.writeByte(t.getId());
        if (t instanceof CompoundTag c) {
            java.util.List<String> keys = new java.util.ArrayList<>(c.getAllKeys());
            java.util.Collections.sort(keys);
            out.writeInt(keys.size());
            for (String k : keys) {
                out.writeUTF(k);
                canonical(c.get(k), out);
            }
        } else if (t instanceof net.minecraft.nbt.ListTag l) {
            out.writeInt(l.size());
            for (net.minecraft.nbt.Tag e : l) canonical(e, out);
        } else {
            t.write(out);
        }
    }

    public static String diff(java.util.Map<String, String> want, java.util.Map<String, String> got) {
        java.util.Set<String> keys = new java.util.TreeSet<>(want.keySet());
        keys.addAll(got.keySet());
        StringBuilder sb = new StringBuilder();
        for (String k : keys)
            if (!java.util.Objects.equals(want.get(k), got.get(k)))
                sb.append(k).append(want.containsKey(k) ? "" : " (only read)").append(got.containsKey(k) ? "" : " (only stored)").append(' ');
        return sb.toString().trim();
    }

    /** -Dlab.chunkio.watch=true: every load / scan / store future of the IOWorkers, those over 10 s logged every 5 s */
    public static final boolean WATCH = Boolean.getBoolean("lab.chunkio.watch");
    private static final java.util.Map<CompletableFuture<?>, Object[]> OUT = new java.util.concurrent.ConcurrentHashMap<>();

    public static <T> CompletableFuture<T> watch(String what, CompletableFuture<T> f) {
        if (!WATCH || f == null) return f;
        Object[] v = {what, System.nanoTime(), Thread.currentThread().getName()};
        OUT.put(f, v);
        f.whenComplete((x, t) -> OUT.remove(f));
        return f;
    }

    static {
        if (WATCH) {
            Thread t = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    long now = System.nanoTime();
                    int old = 0;
                    StringBuilder sb = new StringBuilder();
                    for (Object[] v : OUT.values()) {
                        if (now - (long) v[1] < 10_000_000_000L) continue;
                        if (old++ < 12) sb.append("\n   ").append(v[0]).append(" from ").append(v[2]).append(", ").append((now - (long) v[1]) / 1_000_000_000L).append(" s");
                    }
                    if (old > 0) System.out.println("[sleep/chunkio] " + old + " IO futures over 10 s (of " + OUT.size() + " open):" + sb);
                }
            }, "sleep-chunkio-watch");
            t.setDaemon(true);
            t.start();
        }
    }

    public static void noteBatch(int n) {
        batches.incrementAndGet();
        written.addAndGet(n);
        maxBatch.accumulateAndGet(n, Math::max);
    }

    public static String report() {
        long b = batches.get();
        return (SaveStreams.ON ? SaveStreams.report() + "; " : "") + ChunkIoWriter.report() + "; " + String.format("chunk io: written %d in %d batches (%.1f per batch, max %d), cleared %d; encoded on the pool %d, on the IOWorker %d, stale %d; reads of pending entries %d",
                written.get(), b, b == 0 ? 0 : written.get() / (double) b, maxBatch.get(), cleared.get(), encodedPool.get(), encodedInline.get(), stale.get(), pendingReads.get());
    }

    /** RegionFile.ChunkBuffer's layout: 4 bytes for the length, the version byte, then the stream */
    private static final class Buffer extends ByteArrayOutputStream {
        Buffer(int version) {
            super(8096);
            write(0);
            write(0);
            write(0);
            write(0);
            write(version);
        }
    }
}
