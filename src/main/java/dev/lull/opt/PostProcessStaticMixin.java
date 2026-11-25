package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.StaticShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** StaticShapes: post-processing skips the shape update and the unchanged write of blocks whose shape is static. */
@Mixin(LevelChunk.class)
public abstract class PostProcessStaticMixin {
    /** the last position whose update was skipped (main thread): its setBlock is the unchanged write */
    @Unique private static BlockPos sleep$skippedPos;

    @WrapOperation(method = "postProcessGeneration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;updateFromNeighbourShapes(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState sleep$shape(BlockState state, LevelAccessor level, BlockPos pos, Operation<BlockState> original) {
        if (StaticShapes.ACTIVE && StaticShapes.isStatic(state.getBlock())) {
            sleep$skippedPos = pos;
            StaticShapes.skipped++;
            if (StaticShapes.VERIFY) {
                StaticShapes.verified++;
                if (original.call(state, level, pos) != state) StaticShapes.mismatches++;   // the skipped update must keep the state
            }
            return state;
        }
        sleep$skippedPos = null;
        StaticShapes.ran++;
        if (StaticShapes.CENSUS) {
            long t = System.nanoTime();
            BlockState r = original.call(state, level, pos);
            StaticShapes.count("shape " + state.getBlock().getClass().getSimpleName() + (r == state ? " (same)" : " (changed)"), System.nanoTime() - t);
            return r;
        }
        return original.call(state, level, pos);
    }

    @WrapOperation(method = "postProcessGeneration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean sleep$write(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        if (sleep$skippedPos == pos) {   // the same position object the skipped update had: the state is unchanged
            sleep$skippedPos = null;
            return false;                // what setBlock returns for an unchanged state
        }
        if (StaticShapes.CENSUS) {
            long t = System.nanoTime();
            boolean r = original.call(level, pos, state, flags);
            StaticShapes.count("setBlock " + (r ? "changed" : "same"), System.nanoTime() - t);
            return r;
        }
        return original.call(level, pos, state, flags);
    }

    @WrapOperation(method = "postProcessGeneration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void sleep$fluid(net.minecraft.world.level.material.FluidState fluid, Level level, BlockPos pos, Operation<Void> original) {
        if (StaticShapes.FLUIDS_ACTIVE && StaticShapes.stillWater(fluid, level, pos)) {
            StaticShapes.stillSkipped++;
            if (StaticShapes.VERIFY) {   // the vanilla tick anyway: it must write nothing
                StaticShapes.inVerifiedTick = true;
                try {
                    original.call(fluid, level, pos);
                } finally {
                    StaticShapes.inVerifiedTick = false;
                }
                StaticShapes.verified++;
            }
            return;
        }
        StaticShapes.fluidsRan++;
        if (!StaticShapes.CENSUS) {
            original.call(fluid, level, pos);
            return;
        }
        long t = System.nanoTime();
        original.call(fluid, level, pos);
        StaticShapes.count("fluid tick " + (fluid.isSource() ? "source " : "flowing ") + fluid.getType().getClass().getSimpleName(), System.nanoTime() - t);
    }
}
