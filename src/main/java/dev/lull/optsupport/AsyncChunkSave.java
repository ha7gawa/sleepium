package dev.lull.optsupport;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

/**
 * exp/async-chunk-save (server): the expensive part of saving a chunk on the main thread — encoding every section's
 * block-state and biome palettes (PalettedContainer.pack, ~70 % of ChunkSerializer.write) — moved to ServerReactor's
 * pool. Everything else of ChunkSerializer.write stays on the main thread, in place.
 *
 * <p>On the main thread each palette encode is replaced by a copy of the container (PalettedContainer.copy: its
 * storage and palette) and an empty placeholder tag at the same place in the chunk tag. On the pool the copies are
 * encoded and the placeholders filled (same keys in the encoded tag's order: the same HashMap iteration order, the
 * same bytes on disk), then the tag goes to the storage's IOWorker as vanilla's would.
 *
 * <p>Safety: saves of one chunk reach the IOWorker in the order they were made (a chain per position); a read of a
 * position whose save is still being encoded waits until it is handed to the IOWorker (whose single mailbox then
 * orders the read after the write); flushWorker / close wait for every pending save. LevelChunks and ProtoChunks, only when
 * nobody listens to ChunkDataEvent.Save (a listener may edit the tag on the main thread) and no legacy structure
 * handler is active. {@code -Dlab.asyncsave.verify=true}: every placeholder also encoded the vanilla way on the main
 * thread and compared byte for byte after the pool filled it.
 */
public final class AsyncChunkSave {
    private AsyncChunkSave() {}

    public static final boolean VERIFY = Boolean.getBoolean("lab.asyncsave.verify");
    public static final AtomicLong deferred = new AtomicLong(), sections = new AtomicLong(), verified = new AtomicLong(), mismatches = new AtomicLong(),
            waitedReads = new AtomicLong(), vanilla = new AtomicLong();

    /**
     * Per thread: set around ChunkSerializer.write while this thread defers a save. Other threads write chunks too -
     * C2ME saves on its own workers (11-06, JJThunder To The Max with C2ME: their palettes went into the main thread's
     * list, NPE in fill, crash reports); a thread without its own deferred save always encodes the vanilla way.
     */
    private static final ThreadLocal<List<Object[]>> COLLECTING = new ThreadLocal<>();
    /** per thread: the palette jobs of the chunk tag just written (ChunkMap.save: write, then storage write) */
    private static final ThreadLocal<List<Object[]>> LAST_PENDING = new ThreadLocal<>();
    /** per position: completes once that position's latest deferred save has been handed to the IOWorker */
    private static final ConcurrentHashMap<Long, CompletableFuture<Void>> STORED = new ConcurrentHashMap<>();

    public interface StorageAccess {
        Object sleep$legacyHandler();

        CompletableFuture<java.util.Optional<CompoundTag>> sleep$loadDirect(ChunkPos pos);
    }

    public static boolean shouldDefer(ChunkStorage storage, ChunkAccess chunk) {
        // LevelChunks, and ProtoChunks too (10-08: over half of a teleport's saves - the generated ring around the view
        // unloads unfinished): a chunk is saved with no generation task claiming it (unloads wait for isReadyForSaving,
        // periodic saves skip plain ProtoChunks), and the palettes are copied right here, on the saving thread, as for
        // a LevelChunk
        if (!(chunk instanceof LevelChunk) && !(chunk instanceof net.minecraft.world.level.chunk.ProtoChunk)) return false;
        if (((StorageAccess) storage).sleep$legacyHandler() != null) return false;
        return NeoForge.EVENT_BUS instanceof CompositeEventBus cb && (!cb.mayHaveListeners(ChunkDataEvent.Save.class)
                || onlyCounters(cb)   // the engine's own event counting (/sleepium report, genbench rounds)
                || SaveListeners.ON && SaveListeners.onlyOwnKeys(cb));   // lab.asyncsave.listeners: listeners that keep to their own keys
    }

    /** the listener array last decided, and whether it held only the engine's counting listeners */
    private record Counted(net.neoforged.bus.api.EventListener[] listeners, boolean only) {}

    private static volatile Counted counted;
    public static final AtomicLong countedOnly = new AtomicLong();

    /**
     * 11-21: while /sleepium report or a genbench round records, EventCounter puts a counting listener on every event
     * type - ChunkDataEvent.Save too - and every save went back to the vanilla way on the main thread (a fifth of the
     * genbench's saves, 34 % of the server thread's allocation in its JFR). A counting listener never sees the tag.
     */
    private static boolean onlyCounters(CompositeEventBus cb) {
        net.neoforged.bus.api.EventListener[] ls = cb.listenersOf(ChunkDataEvent.Save.class);
        if (ls == null || ls.length == 0) return false;
        Counted c = counted;
        if (c == null || c.listeners() != ls) {
            boolean only = true;
            for (net.neoforged.bus.api.EventListener l : ls) if (!dev.lull.EventCounter.isCounting(l)) { only = false; break; }
            counted = c = new Counted(ls, only);
        }
        if (c.only()) countedOnly.incrementAndGet();
        return c.only();
    }

    /** ChunkMap.save's ChunkSerializer.write, main thread. */
    public static CompoundTag write(ChunkStorage storage, net.minecraft.server.level.ServerLevel level, ChunkAccess chunk) {
        if (!SaveStats.ON) return write0(storage, level, chunk);
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            return write0(storage, level, chunk);
        } finally {
            SaveStats.add(SaveStats.WRITE, b, t);
        }
    }

    private static CompoundTag write0(ChunkStorage storage, net.minecraft.server.level.ServerLevel level, ChunkAccess chunk) {
        LAST_PENDING.remove();
        LAST_REFERENCE.remove();
        if (!shouldDefer(storage, chunk)) {
            vanilla.incrementAndGet();
            return net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
        }
        // the same chunk written the vanilla way just before and just after: its bytes, for after the pool filled the palettes
        // (only when both agree - the light engine may publish other layers in between: such a chunk is not compared)
        byte[] before = VERIFY_CHUNK ? vanillaWrite(level, chunk) : null;
        List<Object[]> list = new ArrayList<>(48);
        COLLECTING.set(list);
        try {
            CompoundTag tag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
            LAST_PENDING.set(list);
            if (VERIFY_CHUNK) {
                COLLECTING.remove();
                byte[] after = vanillaWrite(level, chunk);
                if (java.util.Arrays.equals(before, after)) LAST_REFERENCE.set(after);
                else chunkUnstable.incrementAndGet();
            }
            return tag;
        } finally {
            COLLECTING.remove();
        }
    }

    /**
     * {@code -Dlab.asyncsave.verifyChunk=true}: every deferred chunk is also written the vanilla way on the main thread
     * (codecs, no placeholders) at the same moment; once the pool filled its palettes, the whole chunk tag's bytes
     * (NbtIo.write, as the region file gets them before compression) are compared with the vanilla tag's.
     */
    public static final boolean VERIFY_CHUNK = Boolean.getBoolean("lab.asyncsave.verifyChunk");
    public static final AtomicLong chunkVerified = new AtomicLong(), chunkMismatches = new AtomicLong(), chunkUnstable = new AtomicLong();
    private static final ThreadLocal<byte[]> LAST_REFERENCE = new ThreadLocal<>();

    /** the chunk written the vanilla way: no deferral, none of SaveWrite / SaveDirect */
    private static byte[] vanillaWrite(net.minecraft.server.level.ServerLevel level, ChunkAccess chunk) {
        List<Object[]> c = COLLECTING.get();
        COLLECTING.remove();
        SaveWrite.vanilla(true);
        try {
            return raw(net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk));
        } finally {
            SaveWrite.vanilla(false);
            if (c != null) COLLECTING.set(c);
        }
    }

    static byte[] raw(CompoundTag t) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream(16384);
            NbtIo.write(t, new DataOutputStream(b));
            return b.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** verifyChunk with SaveListeners: chunks equal to the vanilla write once the listeners' own top-level keys are set aside */
    public static final AtomicLong chunkListenerKeysOnly = new AtomicLong();

    /**
     * With deferring Save listeners (SaveListeners) the stored tag also holds the keys they added after the write
     * (Mekanism's mek_world_gen_version); the vanilla reference is made before the event. Such a chunk is compared with
     * the reference plus exactly those added top-level keys (none of the chunk format's), content-wise (key-sorted).
     */
    private static boolean equalButListenerKeys(CompoundTag tag, byte[] reference) {
        if (!SaveListeners.ON) return false;
        try {
            CompoundTag ref = NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(reference)));
            boolean added = false;
            for (String k : tag.getAllKeys())
                if (!ref.contains(k)) {
                    ref.put(k, tag.get(k).copy());
                    added = true;
                }
            return added && ChunkIo.canonical(ref).equals(ChunkIo.canonical(tag)) && ref.getAllKeys().equals(tag.getAllKeys());
        } catch (Exception e) {
            return false;
        }
    }

    private static void verifyChunk(ChunkPos pos, CompoundTag tag, byte[] reference) {
        chunkVerified.incrementAndGet();
        if (!java.util.Arrays.equals(raw(tag), reference) && equalButListenerKeys(tag, reference)) {
            chunkListenerKeysOnly.incrementAndGet();
            return;
        }
        if (!java.util.Arrays.equals(raw(tag), reference) && chunkMismatches.incrementAndGet() <= 5) {
            String where = "?";
            try {
                CompoundTag ref = NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(reference)));
                where = ChunkIo.diff(ChunkIo.canonical(ref), ChunkIo.canonical(tag));
                if (ref.get("sections") instanceof net.minecraft.nbt.ListTag a && tag.get("sections") instanceof net.minecraft.nbt.ListTag b && a.size() == b.size())
                    for (int i = 0; i < a.size(); i++) {
                        String d = ChunkIo.diff(ChunkIo.canonical(a.getCompound(i)), ChunkIo.canonical(b.getCompound(i)));
                        if (!d.isEmpty()) where += " [section " + i + ": " + d + "]";
                    }
                if (where.isEmpty()) where = "same content, other order";
            } catch (Exception e) {
                where = "unreadable: " + e;
            }
            System.out.println("[sleep/opt] async chunk save: chunk " + pos + " DIFFERS from the vanilla write: " + where);
        }
    }

    /** this thread writes a chunk whose save is deferred: a job of another kind (SaveWrite's tick lists) may join it */
    public static boolean collecting() {
        return COLLECTING.get() != null;
    }

    /** the job filled on the pool before the tag goes to the IOWorker, in the order jobs were added */
    public static void defer(Tag placeholder, Object job) {
        COLLECTING.get().add(new Object[] {placeholder, job, null, null});
    }

    /** ChunkSerializer.write's palette encodes (every Codec.encodeStart there goes through here). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DataResult encode(Codec codec, DynamicOps ops, Object value) {
        List<Object[]> collecting;
        if (!(value instanceof PalettedContainer<?> pc) || ops != NbtOps.INSTANCE || (collecting = COLLECTING.get()) == null) return codec.encodeStart(ops, value);
        CompoundTag placeholder = new CompoundTag();
        Tag reference = VERIFY ? (Tag) codec.encodeStart(ops, value).getOrThrow() : null;
        long b = SaveStats.ON ? SaveStats.bytes() : 0, t = SaveStats.ON ? System.nanoTime() : 0;
        collecting.add(new Object[] {placeholder, SavePalettes.ON ? SavePalettes.snapshot(pc) : pc.copy(), codec, reference});
        if (SaveStats.ON) SaveStats.add(SaveStats.PALETTES, b, t);
        return DataResult.success(placeholder);
    }

    /** ChunkMap.save's storage write, main thread. */
    public static CompletableFuture<Void> store(ChunkStorage storage, ChunkPos pos, CompoundTag tag) {
        if (!SaveStats.ON) return store0(storage, pos, tag);
        long b = SaveStats.bytes(), t = System.nanoTime();
        try {
            return store0(storage, pos, tag);
        } finally {
            SaveStats.add(SaveStats.STORE, b, t);
        }
    }

    private static CompletableFuture<Void> store0(ChunkStorage storage, ChunkPos pos, CompoundTag tag) {
        List<Object[]> jobs = LAST_PENDING.get();
        LAST_PENDING.remove();
        byte[] reference = LAST_REFERENCE.get();
        LAST_REFERENCE.remove();
        if (jobs == null || jobs.isEmpty()) return storage.write(pos, tag);
        deferred.incrementAndGet();
        long key = pos.toLong();
        CompletableFuture<Void> previous = STORED.get(key);
        CompletableFuture<Void> encoded = CompletableFuture.runAsync(reference == null ? () -> fill(jobs) : () -> {
            fill(jobs);
            verifyChunk(pos, tag, reference);
        }, ServerReactor.pool());
        CompletableFuture<CompletableFuture<Void>> handed = (previous == null ? encoded : encoded.thenCombine(previous.exceptionally(t -> null), (a, b) -> null))
                .thenApply(v -> storage.write(pos, tag));
        CompletableFuture<Void> stored = handed.thenApply(f -> (Void) null);
        STORED.put(key, stored);
        stored.whenComplete((v, t) -> STORED.remove(key, stored));
        return handed.thenCompose(f -> f);
    }

    private static void fill(List<Object[]> jobs) {
        if (!SaveStats.ON) {
            fill0(jobs);
            return;
        }
        long b = SaveStats.bytes(), t = System.nanoTime();
        fill0(jobs);
        SaveStats.add(SaveStats.FILL, b, t);
    }

    private static void fill0(List<Object[]> jobs) {
        for (Object[] j : jobs) {
            if (j[1] instanceof SaveWrite.TickJob t) {   // a tick list (SaveWrite, -Dlab.opt.SaveTicks)
                t.fill((net.minecraft.nbt.ListTag) j[0]);
                continue;
            }
            CompoundTag placeholder = (CompoundTag) j[0];
            if (VERIFY && j[1] instanceof SavePalettes.Snapshot snap) SavePalettes.selfTest((Codec<?>) j[2], snap);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Tag enc = j[1] instanceof SavePalettes.Snapshot snap ? SavePalettes.encode((Codec) j[2], snap)
                    : (Tag) ((Codec) j[2]).encodeStart(NbtOps.INSTANCE, j[1]).getOrThrow();
            CompoundTag e = (CompoundTag) enc;
            for (String k : e.getAllKeys()) placeholder.put(k, e.get(k));
            sections.incrementAndGet();
            if (VERIFY) {
                verified.incrementAndGet();
                if (!bytes(placeholder).equals(bytes((Tag) j[3])) && mismatches.incrementAndGet() <= 5)
                    System.out.println("[sleep/opt] async chunk save MISMATCH: " + placeholder + " vs " + j[3]);
            }
        }
    }

    private static String bytes(Tag t) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            if (t instanceof CompoundTag c) NbtIo.write(c, d);
            else t.write(d);
            return java.util.HexFormat.of().formatHex(b.toByteArray());
        } catch (IOException e) {
            return "error " + e;
        }
    }

    /** ChunkStorage.read: a read of a position whose save is still being encoded waits until it reached the IOWorker. */
    public static CompletableFuture<java.util.Optional<CompoundTag>> read(ChunkStorage storage, ChunkPos pos) {
        CompletableFuture<Void> p = STORED.get(pos.toLong());
        StorageAccess s = (StorageAccess) storage;
        if (p == null) return s.sleep$loadDirect(pos);
        waitedReads.incrementAndGet();
        return p.exceptionally(t -> null).thenCompose(v -> s.sleep$loadDirect(pos));
    }

    /** flushWorker / close: every deferred save handed to the IOWorker first. */
    public static void drain() {
        for (CompletableFuture<Void> f : STORED.values()) {
            try {
                f.join();
            } catch (Exception ignored) {
                // reported through the save's own future
            }
        }
    }

    public static String report() {
        return "async chunk save: deferred " + deferred + " chunks (" + sections + " palettes on the pool), vanilla " + vanilla + ", reads that waited "
                + waitedReads + ", deferred with only the engine's counting listeners " + countedOnly
                + (SavePalettes.ON ? ", " + SavePalettes.report() : "") + (VERIFY ? ", verified " + verified + ", mismatches " + mismatches : "")
                + (VERIFY_CHUNK ? ", whole chunks verified " + chunkVerified + ", differing " + chunkMismatches + (SaveListeners.ON ? ", equal but the Save listeners' own keys " + chunkListenerKeysOnly : "") + ", not compared (two vanilla writes differed) " + chunkUnstable : "");
    }
}
