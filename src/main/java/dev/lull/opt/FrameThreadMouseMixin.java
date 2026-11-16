package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FrameThread: the main thread applying the mouse's movement to the player (the turn, then the accumulated delta
 * cleared) is one step for the frame thread, which reads the player's rotation and the delta not yet applied in its
 * camera (FrameThreadCameraMixin): both under FrameThread.TURN_LOCK - never the turn counted twice or not at all.
 * (A lock taken at HEAD and let go at RETURN: a @WrapMethod here, beside the test's expression hooks, left
 * turnPlayer uncalled - the player never turned and the camera shook back each frame, 11-16.)
 */
@Mixin(MouseHandler.class)
public abstract class FrameThreadMouseMixin {
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void sleep$applyLock(CallbackInfo ci) {
        FrameThread.TURN_LOCK.lock();
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
    private void sleep$applyUnlock(CallbackInfo ci) {
        FrameThread.TURN_LOCK.unlock();
    }
}
