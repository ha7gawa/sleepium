package dev.lull.opt;

import dev.lull.optsupport.VoxelStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prototype (lab.voxel.stream): a changed block written into its cell on the GPU; the sections around meshed without an upload. */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class VoxelStreamPointMixin {
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void sleep$voxelPoint(BlockGetter level, BlockPos pos, BlockState old, BlockState now, int flags, CallbackInfo ci) {
        VoxelStream.pointBegin(pos, old, now);
    }

    @Inject(method = "blockChanged", at = @At("RETURN"))
    private void sleep$voxelPointEnd(BlockGetter level, BlockPos pos, BlockState old, BlockState now, int flags, CallbackInfo ci) {
        VoxelStream.causeEnd();
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
    private void sleep$voxelModel(BlockPos pos, BlockState old, BlockState now, CallbackInfo ci) {
        VoxelStream.pointBegin(pos, old, now);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("RETURN"))
    private void sleep$voxelModelEnd(BlockPos pos, BlockState old, BlockState now, CallbackInfo ci) {
        VoxelStream.causeEnd();
    }
}
