package dev.lull.opt;

import com.mojang.datafixers.util.Either;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * -Dlab.opt.IOWorkerRegionScanMixin (11-21, lab): the "old chunks around" scan of a region (IOWorker.isOldChunkAround,
 * asked by STRUCTURE_STARTS so that structures blend into pre-1.18 terrain) as ONE task of the storage's mailbox.
 * Vanilla scans the region's 1024 chunks from a background worker, each with scanChunk(...).join(): 1024 hops to the
 * IO thread and back, one after another, while that region's generation waits - also for a region with no file at all.
 * 11-21 host genbench (JFR, every park recorded): 9223 of 15687 parks in 8 s were these joins (9 regions x 1024), and
 * the first ~1.3 s of a 1024-chunk area ran with the workers idle. Here the same reads run inside one mailbox task, in
 * the same order, each exactly as the per-chunk task did (a pending write's data, else the region file), and the same
 * fields decide the same bit; a store queued meanwhile runs after the whole scan instead of between two of its chunks -
 * an order vanilla's own timing allows as well.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerRegionScanMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Map<ChunkPos, ?> pendingWrites;
    @Shadow @Final private RegionFileStorage storage;

    @Shadow
    private boolean isOldChunk(CompoundTag tag) {
        throw new AssertionError();
    }

    @Shadow
    private <T> CompletableFuture<T> submitTask(Supplier<Either<T, Exception>> task) {
        throw new AssertionError();
    }

    /**
     * @author probe
     * @reason the region's scan as one storage task instead of 1024 joined ones
     */
    @Overwrite
    private CompletableFuture<BitSet> createOldDataForRegion(int regionX, int regionZ) {
        return this.submitTask(() -> Either.left(this.sleep$scanRegion(regionX, regionZ)));
    }

    private BitSet sleep$scanRegion(int regionX, int regionZ) {
        ChunkPos min = ChunkPos.minFromRegion(regionX, regionZ);
        ChunkPos max = ChunkPos.maxFromRegion(regionX, regionZ);
        BitSet bits = new BitSet();
        for (int z = min.z; z <= max.z; z++) {
            for (int x = min.x; x <= max.x; x++) {
                ChunkPos pos = new ChunkPos(x, z);
                CollectFields visitor = new CollectFields(new FieldSelector(IntTag.TYPE, "DataVersion"), new FieldSelector(CompoundTag.TYPE, "blending_data"));
                try {
                    Object pending = this.pendingWrites.get(pos);
                    if (pending != null) {
                        CompoundTag data = dev.lull.optsupport.PendingStoreData.of(pending);
                        if (data != null) data.acceptAsRoot(visitor);
                    } else {
                        this.storage.scanChunk(pos, visitor);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to scan chunk {}", pos, e);
                    continue;
                }
                if (visitor.getResult() instanceof CompoundTag tag && this.isOldChunk(tag)) {
                    bits.set(pos.getRegionLocalZ() * 32 + pos.getRegionLocalX());
                }
            }
        }
        return bits;
    }
}
