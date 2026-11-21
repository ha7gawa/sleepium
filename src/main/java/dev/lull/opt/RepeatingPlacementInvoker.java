package dev.lull.opt;

import dev.lull.optsupport.PlacementWalk;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** WorldgenFeatureAlloc (placement): a count placement's count for PlacementWalk. */
@Mixin(RepeatingPlacement.class)
public interface RepeatingPlacementInvoker extends PlacementWalk.Repeating {
    @Override
    @Invoker("count")
    int sleep$count(RandomSource random, BlockPos pos);
}
