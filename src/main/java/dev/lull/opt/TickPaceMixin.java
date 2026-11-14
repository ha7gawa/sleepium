package dev.lull.opt;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Experiment (stutters phase 2, -Dlab.opt.TickPaceMixin=true): the client's ticks due this frame are spread over the
 * next frames instead of run in a burst (vanilla: up to 10 in one frame after a slow one - the stutter cascade). See
 * dev.lull.optsupport.TickPace.
 */
@Mixin(Minecraft.class)
public abstract class TickPaceMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceTime(JZ)I"))
    private int sleep$pace(DeltaTracker.Timer timer, long millis, boolean renderLevel) {
        int due = timer.advanceTime(millis, renderLevel);
        return renderLevel ? dev.lull.optsupport.TickPace.ticksNow(due) : due;
    }
}
