package dev.lull.opt;

import dev.lull.optsupport.GenGraph;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** GenGraph: the holder's live graph nodes (one slot per status) and its package-private applyStep. */
@Mixin(GenerationChunkHolder.class)
public abstract class GenGraphHolderMixin implements GenGraph.Holder {
    @Unique private final GenGraph.Node[] sleep$slots = new GenGraph.Node[ChunkStatus.getStatusList().size()];

    @Shadow
    abstract CompletableFuture<ChunkResult<ChunkAccess>> applyStep(ChunkStep step, GeneratingChunkMap chunkMap, StaticCache2D<GenerationChunkHolder> cache);

    @Override
    public GenGraph.Node[] sleep$graphSlots() {
        return sleep$slots;
    }

    @Override
    public CompletableFuture<ChunkResult<ChunkAccess>> sleep$applyStep(ChunkStep step, ChunkMap map, StaticCache2D<GenerationChunkHolder> cache) {
        return this.applyStep(step, map, cache);
    }
}
