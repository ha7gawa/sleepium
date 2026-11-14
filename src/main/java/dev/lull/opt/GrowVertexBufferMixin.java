package dev.lull.opt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.GrowBuffer;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** GrowBuffer (-Dlab.gpu.growOnly): vanilla's per-draw vertex and index uploads keep one size per buffer. */
@Mixin(VertexBuffer.class)
public abstract class GrowVertexBufferMixin {
    @Shadow private int vertexBufferId;
    @Shadow private int indexBufferId;
    @Shadow @org.spongepowered.asm.mixin.Final private VertexBuffer.Usage usage;

    @Redirect(method = {"uploadVertexBuffer", "uploadIndexBuffer", "upload"}, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V"), require = 0)
    private void sleep$grow(int target, ByteBuffer data, int usageId) {
        if (usage != VertexBuffer.Usage.DYNAMIC) {   // static buffers (chunk sections): as vanilla
            RenderSystem.glBufferData(target, data, usageId);
            return;
        }
        GrowBuffer.upload(target, target == 34962 ? vertexBufferId : indexBufferId, data.remaining(), org.lwjgl.system.MemoryUtil.memAddress(data));
    }
}
