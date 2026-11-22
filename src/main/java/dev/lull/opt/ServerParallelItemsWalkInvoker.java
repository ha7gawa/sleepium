package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** part of SpecTicks (lab): vanilla's uncached path type of a block (what PathTypeCache stores). */
@Mixin(WalkNodeEvaluator.class)
public interface ServerParallelItemsWalkInvoker {
    @Invoker("getPathTypeFromState")
    static PathType sleep$pathTypeFromState(BlockGetter level, BlockPos pos) {
        throw new AssertionError();
    }
}
