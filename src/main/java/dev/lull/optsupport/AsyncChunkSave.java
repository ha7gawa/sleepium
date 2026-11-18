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
                || SaveListeners.ON && SaveListeners.onlyOwnKeys(cb));   // lab.asyncsave.listeners: listeners that keep to their own keys
    }

    /** ChunkMap.save's ChunkSerializer.write, main thread. */
    public static CompoundTag write(ChunkStorage storage, net.minecraft.server.level.ServerLevel level, ChunkAccess chunk) {
        LAST_PENDING.remove();
        if (!shouldDefer(storage, chunk)) {
            vanilla.incrementAndGet();
            return net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
        }
        List<Object[]> list = new ArrayList<>(48);
        COLLECTING.set(list);
        try {
            CompoundTag tag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
            LAST_PENDING.set(list);
            return tag;
        } finally {
            COLLECTING.remove();
        }
    }

    /** ChunkSerializer.write's palette encodes (every Codec.encodeStart there goes through here). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DataResult encode(Codec codec, DynamicOps ops, Object value) {
        List<Object[]> collecting;
        if (!(value instanceof PalettedContainer<?> pc) || ops != NbtOps.INSTANCE || (collecting = COLLECTING.get()) == null) return codec.encodeStart(ops, value);
        CompoundTag placeholder = new CompoundTag();
        Tag reference = VERIFY ? (Tag) codec.encodeStart(ops, value).getOrThrow() : null;
        collecting.add(new Object[] {placeholder, pc.copy(), codec, reference});
        return DataResult.success(placeholder);
    }

    /** ChunkMap.save's storage write, main thread. */
    public static CompletableFuture<Void> store(ChunkStorage storage, ChunkPos pos, CompoundTag tag) {
        List<Object[]> jobs = LAST_PENDING.get();
        LAST_PENDING.remove();
        if (jobs == null || jobs.isEmpty()) return storage.write(pos, tag);
        deferred.incrementAndGet();
        long key = pos.toLong();
        CompletableFuture<Void> previous = STORED.get(key);
        CompletableFuture<Void> encoded = CompletableFuture.runAsync(() -> fill(jobs), ServerReactor.pool());
        CompletableFuture<CompletableFuture<Void>> handed = (previous == null ? encoded : encoded.thenCombine(previous.exceptionally(t -> null), (a, b) -> null))
                .thenApply(v -> storage.write(pos, tag));
        CompletableFuture<Void> stored = handed.thenApply(f -> (Void) null);
        STORED.put(key, stored);
        stored.whenComplete((v, t) -> STORED.remove(key, stored));
        return handed.thenCompose(f -> f);
    }

    private static void fill(List<Object[]> jobs) {
        for (Object[] j : jobs) {
            CompoundTag placeholder = (CompoundTag) j[0];
            @SuppressWarnings({"unchecked", "rawtypes"})
            Tag enc = (Tag) ((Codec) j[2]).encodeStart(NbtOps.INSTANCE, j[1]).getOrThrow();
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
                + waitedReads + (VERIFY ? ", verified " + verified + ", mismatches " + mismatches : "");
    }
}
