package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FrameThread: LevelRenderer.tick (its tick counter, the stale block-breaking progress dropped every 20 ticks - maps the
 * frame walks) runs at the frame thread's next job start, as the other renderer calls of the client tick (the audit 11-16).
 */
@Mixin(Minecraft.class)
public abstract class FrameThreadRendererTickMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;tick()V"))
    private void sleep$rendererTick(LevelRenderer renderer, Operation<Void> original) {
        if (!dev.lull.optsupport.FrameThread.deferCall(original, renderer)) original.call(renderer);
    }
}
