package dev.lull.opt;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast.entityRandom only (not exact, off: mob sleep needs vanilla's generator): a server entity's own random is Sleepium's FastRandom - its own
 * stream, no atomics (vanilla's LegacyRandomSource: a compareAndSet per draw, an exception when two threads meet).
 * Set before the subclasses' constructors run, so their draws come from it too.
 */
@Mixin(Entity.class)
public abstract class ServerParallelItemsEntityRandomMixin {
    @Shadow @Final @Mutable protected RandomSource random;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$fastRandom(EntityType<?> type, Level level, CallbackInfo ci) {
        if (dev.lull.optsupport.SpecTicks.FAST_ENTITY_RANDOM && level != null && !level.isClientSide()) random = dev.lull.optsupport.FastRandom.fresh();
    }
}
