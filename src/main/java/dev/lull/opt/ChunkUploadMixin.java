package dev.lull.opt;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.ChunkBufferTag;
import dev.lull.optsupport.ChunkMultiDraw;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ChunkMultiDraw: a section layer's mesh is also copied into its region arena before vanilla uploads it. */
@Mixin(VertexBuffer.class)
public abstract class ChunkUploadMixin implements ChunkBufferTag {
    @Unique private SectionRenderDispatcher.RenderSection sleep$section;
    @Unique private RenderType sleep$layer;

    @Override
    public void sleep$tag(SectionRenderDispatcher.RenderSection section, RenderType layer) {
        sleep$section = section;
        sleep$layer = layer;
    }

    @org.spongepowered.asm.mixin.Shadow private com.mojang.blaze3d.vertex.VertexFormat format;

    /** exp/shader-light: a section's vanilla BLOCK buffer (the translucent layer) exposes the recipe bytes (attribute 7) */
    @Inject(method = "upload(Lcom/mojang/blaze3d/vertex/MeshData;)V", at = @At("RETURN"))
    private void sleep$recipeAttribute(MeshData mesh, CallbackInfo ci) {
        if (sleep$section != null && dev.lull.optsupport.LightVolumes.translucentPatched && format == com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK)
            dev.lull.optsupport.LightVolumes.vanillaBufferUploaded();
    }

    @org.spongepowered.asm.mixin.Shadow private com.mojang.blaze3d.vertex.VertexFormat.IndexType indexType;

    /** a translucent section's resort: ChunkMultiDraw's copy takes the new order too */
    @Inject(method = "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V", at = @At("HEAD"))
    private void sleep$resorted(com.mojang.blaze3d.vertex.ByteBufferBuilder.Result result, CallbackInfo ci) {
        if (sleep$section != null && sleep$layer == RenderType.translucent() && ChunkMultiDraw.TRANSLUCENT)
            ChunkMultiDraw.resorted(sleep$section, result.byteBuffer(), indexType);
    }

    @Inject(method = "upload(Lcom/mojang/blaze3d/vertex/MeshData;)V", at = @At("HEAD"), cancellable = true)
    private void sleep$store(MeshData mesh, CallbackInfo ci) {
        if (sleep$section == null) return;
        if (dev.lull.optsupport.RegionDraw.on()) {   // no draw parameters here (GL translators): the opaque layers by region
            if (dev.lull.optsupport.RegionDraw.store(sleep$section, sleep$layer, mesh)) {
                mesh.close();   // what vanilla's upload does at the end
                ci.cancel();
            }
            return;
        }
        boolean ours = ChunkMultiDraw.store(sleep$section, sleep$layer, mesh);
        if (dev.lull.optsupport.LightVolumes.ON) dev.lull.optsupport.LightVolumes.uploaded(sleep$section, sleep$layer, mesh, ours);
        if (ours && ChunkMultiDraw.replacesVanilla(sleep$layer)) {
            mesh.close();   // what vanilla's upload does at the end
            ci.cancel();
        }
    }
}
