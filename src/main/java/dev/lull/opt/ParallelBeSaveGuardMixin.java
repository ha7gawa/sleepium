package dev.lull.opt;

import dev.lull.optsupport.ParallelBeSave;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ParallelBeSave: a pool save that asks the level for a block entity gets null off the main thread (vanilla's
 * Level.getBlockEntity) - another tag than vanilla's: the class goes serial (ParallelBeSave.Touch, caught by the fork).
 */
@Mixin(Level.class)
public abstract class ParallelBeSaveGuardMixin {
    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("HEAD"))
    private void sleep$guard(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (ParallelBeSave.active) ParallelBeSave.touch("Level.getBlockEntity");
    }
}
