package dev.lull.opt;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast only: a server level's random - Sleepium's own per-thread streams (FastRandom.LevelRandom,
 * lab.fast.random, on with lab.fast) or vanilla's behind a lock (LockedRandom) - its draws come from many threads in a run.
 */
@Mixin(Level.class)
public abstract class ServerParallelItemsLevelRandomMixin {
    @Shadow @Final @Mutable public RandomSource random;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$fastRandom(CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.FAST || ((Level) (Object) this).isClientSide()) return;
        random = dev.lull.optsupport.SpecTicks.FAST_RANDOM ? new dev.lull.optsupport.FastRandom.LevelRandom(Thread.currentThread())
                : new dev.lull.optsupport.LockedRandom(random);
    }
}
