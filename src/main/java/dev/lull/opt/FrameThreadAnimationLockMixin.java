package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.AnimationState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread: an AnimationState is written by its entity's tick (start / stop: the clock reset) and by every render of
 * it (updateTime: the clock moved to the frame's age). Vanilla never draws inside a tick; the frame thread does, and an
 * update racing a start lost the reset - the zoo's camel dropped its head for one frame (11-16, a frame burst). Each
 * write under the state's own lock (uncontended: tens of ns). Which of the two goes first stays a matter of timing - a
 * restart shown one frame sooner or later, never half of it.
 */
@Mixin(AnimationState.class)
public abstract class FrameThreadAnimationLockMixin {
    @WrapMethod(method = "start")
    private void sleep$start(int tickCount, Operation<Void> original) {
        synchronized (this) {
            original.call(tickCount);
        }
    }

    @WrapMethod(method = "stop")
    private void sleep$stop(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @WrapMethod(method = "updateTime")
    private void sleep$update(float ageInTicks, float speed, Operation<Void> original) {
        synchronized (this) {
            original.call(ageInTicks, speed);
        }
    }

    @WrapMethod(method = "fastForward")
    private void sleep$forward(int ticks, float speed, Operation<Void> original) {
        synchronized (this) {
            original.call(ticks, speed);
        }
    }
}
