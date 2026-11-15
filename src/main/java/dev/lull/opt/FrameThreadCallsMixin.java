package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * -Dlab.opt.FrameThreadMixin with lab.frameThread.overlap: the client level's calls into the level renderer (section and
 * block marks, chunk loads, block breaking progress) made by the main thread while a frame draws are queued and made
 * when the frame is done - at the call, so whoever implements the renderer's side (vanilla, Sodium's overwrites) is
 * never entered from two threads.
 */
@Mixin(ClientLevel.class)
public abstract class FrameThreadCallsMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;blockChanged(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V"), require = 0)
    private void sleep$blockChanged(LevelRenderer lr, BlockGetter level, BlockPos pos, BlockState o, BlockState n, int flags, Operation<Void> op) {
        if (!FrameThread.deferCall(op, lr, level, pos, o, n, flags)) op.call(lr, level, pos, o, n, flags);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"), require = 0)
    private void sleep$blockDirty(LevelRenderer lr, BlockPos pos, BlockState o, BlockState n, Operation<Void> op) {
        if (!FrameThread.deferCall(op, lr, pos, o, n)) op.call(lr, pos, o, n);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;setSectionDirtyWithNeighbors(III)V"), require = 0)
    private void sleep$sectionDirty(LevelRenderer lr, int x, int y, int z, Operation<Void> op) {
        if (!FrameThread.deferCall(op, lr, x, y, z)) op.call(lr, x, y, z);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V"), require = 0)
    private void sleep$chunkLoaded(LevelRenderer lr, ChunkPos pos, Operation<Void> op) {
        if (!FrameThread.deferCall(op, lr, pos)) op.call(lr, pos);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V"), require = 0)
    private void sleep$breaking(LevelRenderer lr, int id, BlockPos pos, int progress, Operation<Void> op) {
        if (!FrameThread.deferCall(op, lr, id, pos, progress)) op.call(lr, id, pos, progress);
    }
}
