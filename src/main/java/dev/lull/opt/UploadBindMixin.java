package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.ChunkBufferTag;
import dev.lull.optsupport.ChunkMultiDraw;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ChunkMultiDraw (11-17): a section layer's upload whose mesh goes to our arenas skips vanilla's bind of the section's
 * own VAO - never drawn while the arenas draw the layer, and the frame thread's costliest part of an upload (the
 * hardest orbit over the horde: 1.1 ms of the slowest frames' 1.6 ms of uploads; the driver validates each of the
 * hundreds of thousands of section VAOs at its first bind). If the arena refuses the mesh, ChunkUploadMixin binds it
 * before vanilla's upload. The unbind after stays (VAO 0 bound after the uploads, as vanilla).
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class UploadBindMixin {
    @WrapOperation(method = {"lambda$uploadSectionLayer$5", "method_43610"}, require = 0,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;bind()V"))
    private static void sleep$bindLater(VertexBuffer vb, Operation<Void> original) {
        if (vb instanceof ChunkBufferTag t && t.sleep$tagSection() != null && ChunkMultiDraw.replacesVanilla(t.sleep$tagLayer())
                && !dev.lull.optsupport.RegionDraw.on()) {
            ChunkBufferTag.Deferred.buffer = vb;
            ChunkBufferTag.Deferred.skipped++;
            return;
        }
        original.call(vb);
    }
}
