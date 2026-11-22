package dev.lull.opt;

import dev.lull.optsupport.ChunkIo;
import java.io.IOException;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** ChunkIo: the IOWorker's writer opens region files through the storage's own cache. */
@Mixin(RegionFileStorage.class)
public abstract class ChunkIoStorageMixin implements ChunkIo.Storage {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

    @Shadow
    private RegionFile getRegionFile(ChunkPos chunkPos) throws IOException {
        throw new AssertionError();
    }

    @Override
    public RegionFile sleep$region(ChunkPos pos) throws IOException {
        return getRegionFile(pos);
    }

    @Override
    public Iterable<RegionFile> sleep$regions() {
        return regionCache.values();
    }
}
