package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FrameThread: the mouse's movement taken in between entity ticks of a long client tick (pumpMotion). */
@Mixin(ClientLevel.class)
public abstract class FrameThreadPumpMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void sleep$pump(net.minecraft.world.entity.Entity entity, CallbackInfo ci) {
        FrameThread.ticking(entity);   // (before its tick: from now on a frame draws it at the last tick's end)
        FrameThread.pumpMotion();
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"))
    private void sleep$rider(net.minecraft.world.entity.Entity mount, net.minecraft.world.entity.Entity rider, CallbackInfo ci) {
        FrameThread.ticking(rider);
    }
}
