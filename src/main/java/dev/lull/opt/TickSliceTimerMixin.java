package dev.lull.opt;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** TickSlice: the timer's partial tick read and held. */
@Mixin(DeltaTracker.Timer.class)
public abstract class TickSliceTimerMixin implements dev.lull.optsupport.TickSlice.TimerAccess {
    @Shadow private float deltaTickResidual;

    @Override
    public float sleep$residual() {
        return deltaTickResidual;
    }

    @Override
    public void sleep$setResidual(float r) {
        deltaTickResidual = r;
    }
}
