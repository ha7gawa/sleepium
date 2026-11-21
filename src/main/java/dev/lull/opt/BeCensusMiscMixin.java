package dev.lull.opt;

import dev.lull.optsupport.BeCensus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BeCensus (measuring): setChanged inside a block-entity tick (comparators around get updated). */
@Mixin(BlockEntity.class)
public abstract class BeCensusMiscMixin {
    @Inject(method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
    private static void sleep$changed(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        BeCensus.mark(BeCensus.CHANGED);
    }
}
