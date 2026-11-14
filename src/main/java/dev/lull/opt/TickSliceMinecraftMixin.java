package dev.lull.opt;

import dev.lull.optsupport.TickSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TickSlice: the tick's bounds, the frame's end, the owed ticks given back to runTick's loop. 1.20.1: Timer.advanceTime(long). */
@Mixin(Minecraft.class)
public abstract class TickSliceMinecraftMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Timer;advanceTime(J)I"))
    private int sleep$advance(Timer timer, long time) {
        return TickSlice.advance(timer, time);
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void sleep$frameEnd(boolean renderLevel, CallbackInfo ci) {
        TickSlice.frameEnd();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$tickStart(CallbackInfo ci) {
        TickSlice.tickStart();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$tickEnd(CallbackInfo ci) {
        TickSlice.tickEnd();
    }
}
