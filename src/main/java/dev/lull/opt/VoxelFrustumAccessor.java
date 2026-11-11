package dev.lull.opt;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The GPU terrain on 1.20.1: LevelRenderer's visible list (RenderChunkInfo; ChunkMultiDraw.chunkOf gives the chunk). */
@Mixin(LevelRenderer.class)
public interface VoxelFrustumAccessor {
    @Accessor("renderChunksInFrustum")
    ObjectArrayList<?> sleep$frustum();
}
