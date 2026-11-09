package testmods.foreign;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stand-in for another mod's mixin on an interface default Sleepium overrides in a class: VertexConsumer.putBulkData
 * changed there. Only applied with -Dsleep.foreignTest=true. Sleepium's QuadGpuMixin (BufferBuilder's own copy of that
 * default) must give way, or BufferBuilder would skip this change.
 */
@Mixin(VertexConsumer.class)
public interface ForeignBulkDataMixin {
    @Inject(method = "putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[IIZ)V", at = @At("HEAD"))
    private void foreign$bulk(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float r, float g, float b, float a, int[] light, int overlay,
                              boolean readAlpha, CallbackInfo ci) {
    }
}
