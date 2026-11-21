package dev.lull.opt;

import dev.lull.optsupport.FeatScratch;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * WorldgenFeatureAlloc (trees): TreeFeature.updateLeaves walks the six directions of every leaf with
 * {@code for (Direction d : Direction.values())} - a fresh copy of the array per leaf. The loop only reads it: one
 * shared copy, the same elements in the same order.
 */
@Mixin(TreeFeature.class)
public abstract class WorldgenFeatureLeavesMixin {
    @Redirect(method = "updateLeaves", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private static Direction[] sleep$directions() {
        return FeatScratch.DIRECTIONS;
    }
}
