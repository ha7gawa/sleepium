package dev.lull.opt;

import dev.lull.optsupport.BeCensus;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BeCensus (measuring): the level's shared random drawn inside a block-entity tick. */
@Mixin(LegacyRandomSource.class)
public abstract class BeCensusRandomMixin {
    @Inject(method = "next", at = @At("HEAD"))
    private void sleep$draw(int bits, CallbackInfoReturnable<Integer> cir) {
        BeCensus.random(this);
    }
}
