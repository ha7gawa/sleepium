package dev.lull.opt;

import dev.lull.optsupport.BeCensus;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BeCensus (measuring): a block / fluid tick scheduled inside a block-entity tick. */
@Mixin(LevelTicks.class)
public abstract class BeCensusTicksMixin {
    @Inject(method = "schedule", at = @At("HEAD"))
    private void sleep$sched(ScheduledTick<?> tick, CallbackInfo ci) {
        BeCensus.mark(BeCensus.SCHED);
    }
}
