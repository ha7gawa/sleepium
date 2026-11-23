package dev.lull.opt;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decoupled frames: an image closed on the main thread while render work it queued is still waiting (RenderSystem's
 * queue, run by the frame thread at its next frame) is closed in that queue, after the work - vanilla runs the queue on
 * the same thread, so a mod's "queue the upload, close the image" never met a closed image there (11-22: "Image is not
 * allocated" in replayQueue). Off the main thread, or with nothing queued: closed at once.
 */
@Mixin(value = NativeImage.class, remap = false)
public abstract class FrameThreadImageCloseMixin {
    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void sleep$closeInOrder(CallbackInfo ci) {
        if (dev.lull.optsupport.FrameThread.deferClose((NativeImage) (Object) this)) ci.cancel();
    }
}
