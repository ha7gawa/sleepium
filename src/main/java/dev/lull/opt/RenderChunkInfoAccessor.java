package dev.lull.opt;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SectionSet: an occlusion graph info's chunk (LevelRenderer.RenderChunkInfo is package-private). Part of FrustumRegionMixin. */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo")
public interface RenderChunkInfoAccessor {
    @Accessor("chunk")
    ChunkRenderDispatcher.RenderChunk sleep$infoChunk();
}
