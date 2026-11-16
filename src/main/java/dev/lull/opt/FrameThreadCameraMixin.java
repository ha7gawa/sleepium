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
    /** the camera's entity, ticked already in the tick the main thread runs now: at its last tick's end (FrameThread.partial) */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float sleep$partial(float pt, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.world.entity.Entity entity) {
        return FrameThread.partial(entity, pt);
    }

    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow public abstract float getYRot();
    @Shadow public abstract float getXRot();
    @Shadow public abstract boolean isDetached();

    /** the player's rotation read and the turn not yet applied added as one step against the main thread's apply */
    @Inject(method = "setup", at = @At("HEAD"))
    private void sleep$setupLock(CallbackInfo ci) {
        if (FrameThread.onFrameThread()) FrameThread.TURN_LOCK.lock();
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void sleep$extraTurn(CallbackInfo ci) {
        boolean extra = FrameThread.extraNow();
        float dy = 0f;
        if (!isDetached() && FrameThread.turnHere(extra)) {
            dy = FrameThread.turnYaw;
            float dp = FrameThread.turnPitch;
            if (dy != 0f || dp != 0f) setRotation(getYRot() + dy, net.minecraft.util.Mth.clamp(getXRot() + dp, -90f, 90f));
        }
        if (dev.lull.optsupport.TurnLog.ON && FrameThread.onFrameThread()) dev.lull.optsupport.TurnLog.add(extra, getYRot(), getXRot(), dy);
        if (FrameThread.onFrameThread() && FrameThread.TURN_LOCK.isHeldByCurrentThread()) FrameThread.TURN_LOCK.unlock();
    }
}
