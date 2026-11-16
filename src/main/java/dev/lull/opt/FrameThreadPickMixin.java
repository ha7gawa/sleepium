package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FrameThread (overlap): GameRenderer.pick - the crosshair's block / entity, a ray through the live entity sections - is
 * done by the main thread when it hands a frame over (FrameThread.present), not by the frame thread while the main
 * thread moves entities between sections (the audit 11-16). The result is the main thread's input anyway.
 */
@Mixin(GameRenderer.class)
public abstract class FrameThreadPickMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;pick(F)V"))
    private void sleep$pickOnMain(GameRenderer renderer, float pt, Operation<Void> original) {
        if (dev.lull.optsupport.FrameThread.onFrameThread()) return;
        original.call(renderer, pt);
    }
}
