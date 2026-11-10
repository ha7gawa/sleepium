package dev.lull.opt;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** exp/parallel-entities: a draw from the level's random source during a run entity's tick (out of the walk's order) is a conflict. */
@Mixin(LegacyRandomSource.class)
public abstract class ParallelEntityTicksRandomMixin {
    @Inject(method = "next", at = @At("HEAD"))
    private void sleep$draw(int bits, CallbackInfoReturnable<Integer> cir) {
        if (dev.lull.optsupport.ParticleWorkers.active) dev.lull.optsupport.ParallelEntities.draw(this);
    }
}
