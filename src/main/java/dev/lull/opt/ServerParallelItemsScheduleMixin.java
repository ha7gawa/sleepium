package dev.lull.opt;

import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** lab.fast runs and chunk ticks passes: a scheduled block / fluid tick kept for the run's end (the level's tick queues are not for several threads) */
@Mixin(LevelTicks.class)
public abstract class ServerParallelItemsScheduleMixin<T> {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void sleep$keep(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.FAST || !dev.lull.optsupport.SpecTicks.running()) return;
        @SuppressWarnings("unchecked") LevelTicks<T> self = (LevelTicks<T>) (Object) this;
        if (dev.lull.optsupport.SpecTicks.deferWorld(() -> self.schedule(tick))) ci.cancel();
    }
}
