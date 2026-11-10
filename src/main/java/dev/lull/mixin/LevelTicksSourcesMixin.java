package dev.lull.mixin;

import dev.lull.EventSources;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scheduled block/fluid tick requests (LevelTicks exists only for loaded server levels). */
@Mixin(LevelTicks.class)
public abstract class LevelTicksSourcesMixin {
    @Inject(method = "schedule", at = @At("HEAD"))
    private void sleep$schedule(ScheduledTick<?> tick, CallbackInfo ci) {
        EventSources.scheduledTicks++;
        dev.lull.BeTickStats.write();
    }
}
