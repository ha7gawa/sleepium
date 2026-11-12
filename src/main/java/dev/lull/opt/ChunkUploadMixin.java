package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.ChunkBufferTag;
import dev.lull.optsupport.ChunkMultiDraw;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChunkMultiDraw: a section layer's mesh is also copied into its region arena before vanilla uploads it. 1.20.1: one
 * upload(RenderedBuffer) for both a compile's mesh and a translucent resort (1.21.1: upload(MeshData) and
 * uploadIndexBuffer); a resort's buffer is index-only (BufferBuilder.restoreSortState).
 */
@Mixin(VertexBuffer.class)
public abstract class ChunkUploadMixin implements ChunkBufferTag {
    @Unique private ChunkRenderDispatcher.RenderChunk sleep$section;
    @Unique private RenderType sleep$layer;

    @Override
    public void sleep$tag(ChunkRenderDispatcher.RenderChunk section, RenderType layer) {
        sleep$section = section;
        sleep$layer = layer;
    }

    @org.spongepowered.asm.mixin.Shadow private com.mojang.blaze3d.vertex.VertexFormat format;
    @org.spongepowered.asm.mixin.Shadow private int vertexBufferId;

    @org.spongepowered.asm.mixin.Shadow
    public abstract boolean isInvalid();

    /**
     * exp/shader-light: a section's vanilla BLOCK buffer (the translucent layer) exposes the recipe bytes (attribute 7).
     * 1.20.1's upload binds the vertex buffer only when it sends vertices or the format changed: bound here, as the
     * attribute pointer reads the GL_ARRAY_BUFFER binding (the VAO is bound by uploadChunkLayer).
     */
    @Inject(method = "upload", at = @At("RETURN"))
    private void sleep$recipeAttribute(BufferBuilder.RenderedBuffer mesh, CallbackInfo ci) {
        if (sleep$section != null && dev.lull.optsupport.LightVolumes.translucentPatched && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK
                && !isInvalid()) {
            com.mojang.blaze3d.platform.GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, vertexBufferId);
            dev.lull.optsupport.LightVolumes.vanillaBufferUploaded();
        }
    }

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void sleep$store(BufferBuilder.RenderedBuffer mesh, CallbackInfo ci) {
        if (sleep$section == null || isInvalid()) return;   // (an invalid buffer: vanilla's upload does nothing)
        if (mesh.drawState().indexOnly()) {
            // a translucent section's resort: ChunkMultiDraw's copy takes the new order too
            if (sleep$layer == RenderType.translucent() && ChunkMultiDraw.TRANSLUCENT) {
                var ds = mesh.drawState();
                ChunkMultiDraw.resorted(sleep$section, ds.sequentialIndex() ? null : mesh.indexBuffer(), ds.indexType());
            }
            return;
        }
        if (dev.lull.optsupport.RegionDraw.on()) {   // no draw parameters here (GL translators): the opaque layers by region
            if (dev.lull.optsupport.RegionDraw.store(sleep$section, sleep$layer, mesh)) {
                mesh.release();   // what vanilla's upload does at the end
                ci.cancel();
            }
            return;
        }
        boolean ours = ChunkMultiDraw.store(sleep$section, sleep$layer, mesh);
        if (dev.lull.optsupport.LightVolumes.ON) dev.lull.optsupport.LightVolumes.uploaded(sleep$section, sleep$layer, mesh, ours);
        if (ours && ChunkMultiDraw.replacesVanilla(sleep$layer)) {
            mesh.release();   // what vanilla's upload does at the end
            ci.cancel();
        }
    }
}
