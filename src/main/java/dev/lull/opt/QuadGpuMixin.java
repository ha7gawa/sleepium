package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.optsupport.QuadGpu;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * exp/quad-gpu (client): every putBulkData overload ends in Forge's full one (colour multipliers, alpha); BufferBuilder's
 * own copy of it hands the quad to QuadGpu (a run drawn from VRAM) or writes it as the default does. The short
 * overloads (vanilla's, Forge's) call it with the builder's arrays instead of new ones. The builder's open run is closed
 * before it is ended.
 */
@Mixin(BufferBuilder.class)
public abstract class QuadGpuMixin implements VertexConsumer {
    /** this builder's arrays for the short overloads (one thread writes a builder at a time; read at once, never kept) */
    // (made at first use: Mixin 0.8.5 cannot merge an array initialiser into the target's constructor)
    @Unique private float[] sleep$ones;
    @Unique private int[] sleep$light;

    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] colorMuls, float red, float green, float blue, float alpha, int[] lights,
                            int overlay, boolean mulColor) {
        if (!QuadGpu.put((BufferBuilder) (Object) this, pose, quad, colorMuls, red, green, blue, alpha, lights, overlay, mulColor))
            QuadGpu.vanilla(this, pose, quad, colorMuls, red, green, blue, alpha, lights, overlay, mulColor);
    }

    /** vanilla's short overload: brightness 1 and the light four times, without new arrays */
    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue, int light, int overlay) {
        putBulkData(pose, quad, sleep$ones(), red, green, blue, 1.0F, sleep$lights(light), overlay, false);
    }

    /** Forge's IForgeVertexConsumer default (alpha, readExistingColor): the same */
    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue, float alpha, int light, int overlay, boolean readExistingColor) {
        putBulkData(pose, quad, sleep$ones(), red, green, blue, alpha, sleep$lights(light), overlay, readExistingColor);
    }

    @Unique
    private float[] sleep$ones() {
        float[] f = sleep$ones;
        if (f == null) sleep$ones = f = new float[4];
        f[0] = f[1] = f[2] = f[3] = 1.0F;   // refilled each time: nothing downstream may count on them staying
        return f;
    }

    @Unique
    private int[] sleep$lights(int light) {
        int[] l = sleep$light;
        if (l == null) sleep$light = l = new int[4];
        l[0] = l[1] = l[2] = l[3] = light;
        return l;
    }

    @Inject(method = "end", at = @At("HEAD"))
    private void sleep$closeRun(CallbackInfoReturnable<BufferBuilder.RenderedBuffer> cir) {
        QuadGpu.beforeBuild((BufferBuilder) (Object) this);
    }
}
