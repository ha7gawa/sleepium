package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ObservedModSleep (applied only with -Dlab.sleep.ObservedModSleep=true): a block told that a neighbour changed (a
 * redstone signal, a block placed or removed next to it) - an observed-only sleeper there wakes: its blind probes do not
 * show what it reads from its neighbours (SleepEngine.neighbourAt).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class NeighbourWakeMixin {
    @Inject(method = "handleNeighborChanged", at = @At("HEAD"))
    private void sleep$neighbour(Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving, CallbackInfo ci) {
        dev.lull.optsupport.sleep.SleepEngine.neighbourAt(level, pos);
    }
}
