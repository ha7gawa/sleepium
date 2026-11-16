package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FrameThread: on the frame thread, while it draws, Minecraft.getFrameTime gives the tick-end partial tick. */
@Mixin(Minecraft.class)
public abstract class FrameThreadTimerMixin {
    @ModifyReturnValue(method = "getFrameTime", at = @At("RETURN"))
    private float sleep$tickEndPt(float pt) {
        return FrameThread.drawingHere() ? FrameThread.tickEndPt() : pt;
    }
}
