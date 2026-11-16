package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.frameThread.extraTurn: an extra frame's camera turned by the cursor's move since the tick began (FrameThread). */
@Mixin(Camera.class)
public abstract class FrameThreadCameraMixin {
    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow public abstract float getYRot();
    @Shadow public abstract float getXRot();
    @Shadow public abstract boolean isDetached();

    @Inject(method = "setup", at = @At("TAIL"))
    private void sleep$extraTurn(CallbackInfo ci) {
        boolean extra = FrameThread.extraNow();
        float dy = 0f;
        if (FrameThread.EXTRA_TURN && extra && !isDetached()) {
            dy = FrameThread.turnYaw;
            float dp = FrameThread.turnPitch;
            if (dy != 0f || dp != 0f) setRotation(getYRot() + dy, net.minecraft.util.Mth.clamp(getXRot() + dp, -90f, 90f));
        }
        if (dev.lull.optsupport.TurnLog.ON && FrameThread.onFrameThread()) dev.lull.optsupport.TurnLog.add(extra, getYRot(), getXRot(), dy);
    }
}
