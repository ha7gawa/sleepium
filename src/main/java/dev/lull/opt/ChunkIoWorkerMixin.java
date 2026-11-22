package dev.lull.opt;

import com.mojang.datafixers.util.Either;
import dev.lull.optsupport.ChunkIo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ChunkIo (see dev.lull.optsupport.ChunkIo): stored tags encoded on the pool, the queue written in batches per
 * region file. pendingWrites and its entries are touched on this IOWorker's mailbox thread only, as in vanilla.
 */
@Mixin(IOWorker.class)
public abstract class ChunkIoWorkerMixin implements dev.lull.optsupport.ChunkIoWriter.Worker {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private AtomicBoolean shutdownRequested;
    @Shadow @Final private ProcessorMailbox<StrictQueue.IntRunnable> mailbox;
    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private Map<ChunkPos, Object> pendingWrites;

    /** encodes started on the pool whose result has not reached the mailbox yet */
    @Unique private final AtomicInteger sleep$inFlight = new AtomicInteger();
    @Unique private final Map<Long, CompletableFuture<Void>> sleep$encodes = new ConcurrentHashMap<>();
    @Unique private final Map<Long, Map<String, String>> sleep$verify = ChunkIo.VERIFY ? new ConcurrentHashMap<>() : null;
    /** ChunkIoWriter's cadence: region files of this storage are not fully durable; a commit task is in the mailbox */
    @Unique private volatile boolean sleep$uncommitted;
    @Unique private final AtomicBoolean sleep$commitQueued = new AtomicBoolean();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$registerWriter(CallbackInfo ci) {
        dev.lull.optsupport.ChunkIoWriter.register(this);
    }

    @Override
    public void sleep$commitDue() {
        if (!sleep$uncommitted || shutdownRequested.get() || !sleep$commitQueued.compareAndSet(false, true)) return;
        mailbox.tell(new StrictQueue.IntRunnable(1, this::sleep$commitTask));   // BACKGROUND: after loads and stores
    }

    /** mailbox thread: a round for every open region file whose cadence is over */
    @Unique
    private void sleep$commitTask() {
        sleep$commitQueued.set(false);
        boolean pending = false;
        long now = System.nanoTime();
        for (RegionFile r : ((ChunkIo.Storage) (Object) this.storage).sleep$regions()) {
            try {
                pending |= ((ChunkIo.Region) r).sleep$commitDue(now);
            } catch (IOException e) {
                LOGGER.error("ChunkIo: committing region file failed", e);
            }
        }
        sleep$uncommitted = pending;
    }

    @Shadow
    private void tellStorePending() {
        throw new AssertionError();
    }

    @Shadow
    private <T> CompletableFuture<T> submitTask(Supplier<Either<T, Exception>> task) {
        throw new AssertionError();
    }

    /** counted before vanilla queues the store: its task may run, and post the writer, before store() returns */
    @Inject(method = "store", at = @At("HEAD"))
    private void sleep$count(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (tag != null && !(tag instanceof ChunkIo.Encoded)) sleep$inFlight.incrementAndGet();
    }

    /** after vanilla queued the store (FOREGROUND, FIFO): the encode's result is queued behind it, never before */
    @Inject(method = "store", at = @At("RETURN"))
    private void sleep$encode(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ChunkIo.watch(this.storage.info().type() + " store " + pos + (tag == null ? " (clear)" : ""), cir.getReturnValue());
        if (tag == null) {
            if (sleep$verify != null) sleep$verify.remove(pos.toLong());
            return;
        }
        if (tag instanceof ChunkIo.Encoded enc) {   // already made (StoredRing): the writer takes its bytes as they are
            if (sleep$verify != null) {
                try {
                    sleep$verify.put(pos.toLong(), ChunkIo.canonical(ChunkIo.decode(enc.record)));
                } catch (IOException e) {
                    LOGGER.error("ChunkIo verify: a made record of {} does not decode", pos, e);
                }
            }
            return;
        }
        if (sleep$verify != null) sleep$verify.put(pos.toLong(), ChunkIo.canonical(tag));
        long key = pos.toLong();
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
            byte[] bytes = null;
            try {
                bytes = ChunkIo.encode(tag, RegionFileVersion.getSelected());
                ChunkIo.encodedPool.incrementAndGet();
            } catch (Throwable t) {
                LOGGER.warn("ChunkIo: encoding chunk {} on the pool failed, the IOWorker encodes it", pos, t);
            }
            byte[] done = bytes;
            mailbox.tell(new StrictQueue.IntRunnable(0, () -> sleep$encoded(pos, tag, done)));
        }, dev.lull.api.Pools.background());
        sleep$encodes.put(key, f);
        f.whenComplete((v, t) -> sleep$encodes.remove(key, f));
    }

    /**
     * mailbox thread: the entry takes the bytes if it still holds that very tag (a newer store replaced it otherwise);
     * its tag becomes {@link ChunkIo#DROPPED}. Vanilla's store task only ever sets the tag (a newer one, or null for
     * a removal), so an entry reads unambiguously: DROPPED - its bytes are current; null - clear the chunk; any other
     * tag - a newer store whose encode is on the pool.
     */
    @Unique
    private void sleep$encoded(ChunkPos pos, CompoundTag tag, byte[] bytes) {
        sleep$inFlight.decrementAndGet();
        Object o = pendingWrites.get(pos);
        if (o instanceof ChunkIo.Entry e && e.sleep$data() == tag && bytes != null) {
            e.sleep$setBytes(bytes);
            e.sleep$setData(ChunkIo.DROPPED);
        } else {
            ChunkIo.stale.incrementAndGet();
        }
        tellStorePending();
    }

    /**
     * @author probe (ChunkIo)
     * @reason a pending entry may hold its bytes instead of the tag
     */
    @Overwrite
    public CompletableFuture<Optional<CompoundTag>> loadAsync(ChunkPos chunkPos) {
        return ChunkIo.watch(this.storage.info().type() + " load " + chunkPos, this.<Optional<CompoundTag>>submitTask(() -> {
            Object o = this.pendingWrites.get(chunkPos);
            try {
                if (o instanceof ChunkIo.Entry e) {
                    ChunkIo.pendingReads.incrementAndGet();
                    CompoundTag data = e.sleep$data();
                    byte[] rec = ChunkIo.record(e, data);
                    if (rec != null) return Either.left(Optional.of(ChunkIo.decode(rec)));
                    return Either.left(Optional.ofNullable(data == null ? null : data.copy()));
                }
                return Either.left(Optional.ofNullable(this.storage.read(chunkPos)));
            } catch (Exception exception) {
                LOGGER.warn("Failed to read chunk {}", chunkPos, exception);
                return Either.right(exception);
            }
        }));
    }

    /**
     * @author probe (ChunkIo)
     * @reason a pending entry may hold its bytes instead of the tag
     */
    @Overwrite
    public CompletableFuture<Void> scanChunk(ChunkPos chunkPos, StreamTagVisitor visitor) {
        return ChunkIo.watch(this.storage.info().type() + " scan " + chunkPos, this.<Void>submitTask(() -> {
            try {
                Object o = this.pendingWrites.get(chunkPos);
                if (o instanceof ChunkIo.Entry e) {
                    CompoundTag data = e.sleep$data();
                    byte[] rec = ChunkIo.record(e, data);
                    if (rec != null) ChunkIo.scan(rec, visitor);
                    else if (data != null) data.acceptAsRoot(visitor);
                } else {
                    this.storage.scanChunk(chunkPos, visitor);
                }
                return Either.left(null);
            } catch (Exception exception) {
                LOGGER.warn("Failed to bulk scan chunk {}", chunkPos, exception);
                return Either.right(exception);
            }
        }));
    }

    /**
     * @author probe (ChunkIo)
     * @reason every ready entry written at once, grouped by region file (one sync pair per region, not two per chunk)
     */
    @Overwrite
    private void storePendingChunk() {
        if (this.pendingWrites.isEmpty()) return;
        List<ChunkPos> pos = new ArrayList<>();
        List<ChunkIo.Entry> entries = new ArrayList<>();
        for (Iterator<Map.Entry<ChunkPos, Object>> it = this.pendingWrites.entrySet().iterator(); it.hasNext() && pos.size() < ChunkIo.BATCH; ) {
            Map.Entry<ChunkPos, Object> me = it.next();
            ChunkIo.Entry e = (ChunkIo.Entry) me.getValue();
            CompoundTag data = e.sleep$data();
            if (data != null && data != ChunkIo.DROPPED && !(data instanceof ChunkIo.Encoded)) continue;   // its encode is on the pool
            it.remove();
            pos.add(me.getKey());
            entries.add(e);
        }
        if (pos.isEmpty()) {
            if (sleep$inFlight.get() > 0) return;   // each encode's result posts the writer again
            // nothing on the pool and nothing ready (an encode failed): the oldest entry encoded here, as vanilla does
            Iterator<Map.Entry<ChunkPos, Object>> it = this.pendingWrites.entrySet().iterator();
            Map.Entry<ChunkPos, Object> me = it.next();
            it.remove();
            pos.add(me.getKey());
            entries.add((ChunkIo.Entry) me.getValue());
        }
        sleep$write(pos, entries);
        this.tellStorePending();
    }

    @Unique
    private void sleep$write(List<ChunkPos> pos, List<ChunkIo.Entry> entries) {
        long t0 = dev.lull.IoScout.ON ? System.nanoTime() : 0;
        int n = pos.size();
        // region by region, in the queue's order of their first chunk
        Map<Long, List<Integer>> byRegion = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            byRegion.computeIfAbsent(ChunkPos.asLong(pos.get(i).getRegionX(), pos.get(i).getRegionZ()), k -> new ArrayList<>()).add(i);
        for (List<Integer> group : byRegion.values()) {
            ChunkPos[] p = new ChunkPos[group.size()];
            byte[][] data = new byte[group.size()][];
            ChunkIo.Entry[] done = new ChunkIo.Entry[group.size()];
            int k = 0;
            try {
                RegionFile region = ((ChunkIo.Storage) (Object) this.storage).sleep$region(pos.get(group.get(0)));
                int version = ((ChunkIo.Region) region).sleep$versionId();
                for (int i : group) {
                    ChunkIo.Entry e = entries.get(i);
                    CompoundTag tag = e.sleep$data();
                    byte[] bytes = tag == null ? null : ChunkIo.record(e, tag);
                    if (tag == null) {   // a store of null: the chunk is cleared, as vanilla does
                        try {
                            region.clear(pos.get(i));
                            ChunkIo.cleared.incrementAndGet();
                            e.sleep$result().complete(null);
                        } catch (Exception exception) {
                            LOGGER.error("Failed to store chunk {}", pos.get(i), exception);
                            e.sleep$result().completeExceptionally(exception);
                        }
                        continue;
                    }
                    try {
                        if (bytes == null || bytes[4] != version) {   // not encoded yet, or for another region version
                            bytes = ChunkIo.encode(bytes == null ? tag : ChunkIo.decode(bytes), RegionFileVersion.fromId(version));
                            ChunkIo.encodedInline.incrementAndGet();
                        }
                    } catch (Exception exception) {
                        LOGGER.error("Failed to store chunk {}", pos.get(i), exception);
                        e.sleep$result().completeExceptionally(exception);
                        continue;
                    }
                    p[k] = pos.get(i);
                    data[k] = bytes;
                    done[k++] = e;
                }
                if (k > 0) ((ChunkIo.Region) region).sleep$writeBatch(p, data, k);
                ChunkIo.noteBatch(k);
                for (int i = 0; i < k; i++) done[i].sleep$result().complete(null);
            } catch (Exception exception) {
                for (int i : group) {
                    ChunkIo.Entry e = entries.get(i);
                    if (e.sleep$result().isDone()) continue;
                    LOGGER.error("Failed to store chunk {}", pos.get(i), exception);
                    e.sleep$result().completeExceptionally(exception);
                }
            }
        }
        if (dev.lull.optsupport.ChunkIoWriter.CADENCE_MS > 0) sleep$uncommitted = true;
        if (dev.lull.IoScout.ON) dev.lull.IoScout.batch(this.storage.info().type(), n, System.nanoTime() - t0, this.pendingWrites.size());
    }

    /** every encode's result in the mailbox before vanilla queues the SHUTDOWN task (the writer runs ahead of it) */
    @Inject(method = "close", at = @At("HEAD"))
    private void sleep$drainEncodes(CallbackInfo ci) {
        for (CompletableFuture<Void> f : sleep$encodes.values()) {
            try {
                f.join();
            } catch (Exception ignored) {
                // the failure went to the log and the IOWorker encodes that chunk itself
            }
        }
    }

    /** -Dlab.chunkio.verify: every stored position read back through vanilla's storage and compared */
    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;close()V"))
    private void sleep$verifyAndReport(CallbackInfo ci) {
        String type = this.storage.info().type();
        if (sleep$verify != null) {
            int ok = 0, bad = 0, missing = 0;
            for (Map.Entry<Long, Map<String, String>> v : sleep$verify.entrySet()) {
                try {
                    CompoundTag back = this.storage.read(new ChunkPos(v.getKey()));
                    Map<String, String> got = back == null ? null : ChunkIo.canonical(back);
                    if (got == null) missing++;
                    else if (got.equals(v.getValue())) ok++;
                    else if (bad++ < 5) LOGGER.error("ChunkIo verify: chunk {} read back differs in {}", new ChunkPos(v.getKey()), ChunkIo.diff(v.getValue(), got));
                } catch (IOException e) {
                    bad++;
                    LOGGER.error("ChunkIo verify: chunk {} unreadable", new ChunkPos(v.getKey()), e);
                }
            }
            System.out.println("[sleep/opt] chunk io verify (" + type + "): " + ok + " equal, " + bad + " differ, " + missing + " missing of " + sleep$verify.size());
        }
        System.out.println("[sleep/opt] " + ChunkIo.report() + " (all IOWorkers so far)");
    }
}
