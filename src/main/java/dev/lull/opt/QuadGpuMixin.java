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

    /** this builder's arrays for the short overloads (one thread writes a builder at a time; read at once, never kept) */
    @org.spongepowered.asm.mixin.Unique private final float[] sleep$ones = {1.0F, 1.0F, 1.0F, 1.0F};
    @org.spongepowered.asm.mixin.Unique private final int[] sleep$light = new int[4];

    /** VertexConsumer's default: the full overload with all-ones brightness and the light four times - here without new arrays */
    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue, float alpha, int packedLight, int packedOverlay) {
        putBulkData(pose, quad, red, green, blue, alpha, packedLight, packedOverlay, false);
    }

    /** NeoForge's IVertexConsumerExtension default (readExistingColor): the same, without new arrays */
    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue, float alpha, int packedLight, int packedOverlay,
                            boolean readExistingColor) {
        float[] ones = sleep$ones;
        ones[0] = ones[1] = ones[2] = ones[3] = 1.0F;   // refilled each time: nothing downstream may count on them staying
        int[] l = sleep$light;
        l[0] = l[1] = l[2] = l[3] = packedLight;
        putBulkData(pose, quad, ones, red, green, blue, alpha, l, packedOverlay, readExistingColor);
    }

    @Inject(method = "build", at = @At("HEAD"))
    private void sleep$closeRun(CallbackInfoReturnable<MeshData> cir) {
        QuadGpu.beforeBuild((BufferBuilder) (Object) this);
    }
}
