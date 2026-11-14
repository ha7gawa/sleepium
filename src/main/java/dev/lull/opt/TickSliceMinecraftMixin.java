package dev.lull.opt;

import dev.lull.optsupport.TickSlice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TickSlice: the tick's bounds, the frame's end, the owed ticks given back to runTick's loop. */
@Mixin(Minecraft.class)
public abstract class TickSliceMinecraftMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceTime(JZ)I"))
    private int sleep$advance(DeltaTracker.Timer timer, long time, boolean advanceGame) {
        return TickSlice.advance(timer, time, advanceGame);
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
