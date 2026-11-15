package dev.lull.opt;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.glWorker.lateJoin: the GL worker's job waited for at the frame's first terrain layer (the first atlas reads), not before the frame. */
@Mixin(LevelRenderer.class)
public abstract class GlWorkerLayerMixin {
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void sleep$glWorkerLateJoin(CallbackInfo ci) {
        dev.lull.optsupport.GlWorker.joinIfBusy();
    }
}
