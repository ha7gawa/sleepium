package dev.lull.opt;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.optsupport.QuadArrays;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** QuadArrays: putQuadData's brightness / lightmap arrays are the thread's own, refilled per quad (see there). */
@Mixin(ModelBlockRenderer.class)
public abstract class QuadDataArraysMixin {
    @Shadow @Final private BlockColors blockColors;

    /**
     * @author sleepium
     * @reason the same body with the thread's arrays instead of two new ones a quad
     */
    @Overwrite
    private void putQuadData(BlockAndTintGetter level, BlockState state, BlockPos pos, VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad,
                             float brightness0, float brightness1, float brightness2, float brightness3,
                             int lightmap0, int lightmap1, int lightmap2, int lightmap3, int packedOverlay) {
        float f;
        float f1;
        float f2;
        if (quad.isTinted()) {
            int i = this.blockColors.getColor(state, level, pos, quad.getTintIndex());
            f = (float) (i >> 16 & 0xFF) / 255.0F;
            f1 = (float) (i >> 8 & 0xFF) / 255.0F;
            f2 = (float) (i & 0xFF) / 255.0F;
        } else {
            f = 1.0F;
            f1 = 1.0F;
            f2 = 1.0F;
        }
        QuadArrays q = QuadArrays.mine();
        float[] b = q.bright;
        b[0] = brightness0;
        b[1] = brightness1;
        b[2] = brightness2;
        b[3] = brightness3;
        int[] l = q.light;
        l[0] = lightmap0;
        l[1] = lightmap1;
        l[2] = lightmap2;
        l[3] = lightmap3;
        consumer.putBulkData(pose, quad, b, f, f1, f2, 1.0F, l, packedOverlay, true);
    }
}
