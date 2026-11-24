package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** lab.fast chunk ticks: a mod's block's (or fluid's) random tick during the pass done whole at its end, on the server thread */
@Mixin(ServerLevel.class)
public abstract class ServerParallelItemsRandomTickMixin {
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void sleep$block(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> op) {
        if (dev.lull.optsupport.SpecTicks.running() && dev.lull.optsupport.SpecTicks.keepModRandomTick(state.getBlock().getClass(), () -> op.call(state, level, pos, level.random))) return;
        op.call(state, level, pos, random);
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void sleep$fluid(FluidState state, Level level, BlockPos pos, RandomSource random, Operation<Void> op) {
        if (dev.lull.optsupport.SpecTicks.running() && dev.lull.optsupport.SpecTicks.keepModRandomTick(state.getType().getClass(), () -> op.call(state, level, pos, level.random))) return;
        op.call(state, level, pos, random);
    }
}
