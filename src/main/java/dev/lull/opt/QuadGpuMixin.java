package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.optsupport.QuadGpu;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * exp/quad-gpu (client): every putBulkData overload ends in this one; BufferBuilder's own copy of VertexConsumer's
 * default hands the quad to QuadGpu (a run drawn from VRAM) or writes it as the default does. The builder's open run is
 * closed before it is built.
 */
@Mixin(BufferBuilder.class)
public abstract class QuadGpuMixin implements VertexConsumer {
    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] lightmap,
                            int packedOverlay, boolean readAlpha) {
        if (!QuadGpu.put((BufferBuilder) (Object) this, pose, quad, brightness, red, green, blue, alpha, lightmap, packedOverlay, readAlpha))
            QuadGpu.vanilla(this, pose, quad, brightness, red, green, blue, alpha, lightmap, packedOverlay, readAlpha);
    }

    @Inject(method = "build", at = @At("HEAD"))
    private void sleep$closeRun(CallbackInfoReturnable<MeshData> cir) {
        QuadGpu.beforeBuild((BufferBuilder) (Object) this);
    }
}
