package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.FrameProfile;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FrameProfile (measuring): the entity loop's culling (shouldRender) and its compiled-section test as sections. */
@Mixin(LevelRenderer.class)
public abstract class FrameProfileCullMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean sleep$cull(EntityRenderDispatcher d, Entity e, Frustum f, double x, double y, double z, Operation<Boolean> original) {
        if (!FrameThread.drawingHere()) return original.call(d, e, f, x, y, z);
        FrameProfile.INSTANCE.push("cull");
        try {
            return original.call(d, e, f, x, y, z);
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean sleep$compiledCheck(LevelRenderer lr, BlockPos p, Operation<Boolean> original) {
        if (!FrameThread.drawingHere()) return original.call(lr, p);
        FrameProfile.INSTANCE.push("compiled_check");
        try {
            return original.call(lr, p);
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }
}
