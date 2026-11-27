package dev.lull.opt;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lull.optsupport.RenderCache;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/** exp/render-cache (client): the block entity renderer call of setupAndRender goes through RenderCache. Key: block state. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class RenderCacheBlockEntityMixin {
    @WrapOperation(method = "setupAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void sleep$cached(BlockEntityRenderer r, BlockEntity be, float pt, PoseStack pose, MultiBufferSource buffers, int light, int overlay, Operation<Void> original) {
        if (dev.lull.optsupport.ModProfile.ON_CLIENT) {   // the Mods page's recording / the HUD's sample: this type's drawing time (any thread)
            long t0 = System.nanoTime();
            try {
                draw(r, be, pt, pose, buffers, light, overlay, original);
            } finally {
                dev.lull.optsupport.ModProfile.add(dev.lull.optsupport.ModProfile.BE_DRAW, be.getType(), System.nanoTime() - t0);
            }
            return;
        }
        draw(r, be, pt, pose, buffers, light, overlay, original);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void draw(BlockEntityRenderer r, BlockEntity be, float pt, PoseStack pose, MultiBufferSource buffers, int light, int overlay, Operation<Void> original) {
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            RenderCache.render(be, r.getClass(), (p, b, t) -> original.call(r, be, t, p, b, light, overlay), pt, pose, buffers, light, overlay, 0f,
                    RenderCache.beKey(be, false), null);
            return;
        }
        var d = dev.lull.optsupport.RenderCacheDraws.enterBe(r, be, light, overlay, original);   // no lambda per call
        try {
            RenderCache.render(be, r.getClass(), d, pt, pose, buffers, light, overlay, 0f, RenderCache.beKey(be, true), null);
        } finally {
            dev.lull.optsupport.RenderCacheDraws.exitBe(d);
        }
    }
}

