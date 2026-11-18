package dev.lull.opt;

import dev.lull.optsupport.BeCensus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BeCensus (measuring): each block-entity tick of the level's walk, and the level's effects reached inside it. */
@Mixin(Level.class)
public abstract class BeCensusLevelMixin {
    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void sleep$tick(TickingBlockEntity t) {
        if (!BeCensus.ON) {
            t.tick();
            return;
        }
        BeCensus.begin((Level) (Object) this, t.getPos(), t.getType());
        try {
            t.tick();
        } finally {
            BeCensus.end();
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void sleep$write(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        BeCensus.mark(BeCensus.WRITE);
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"))
    private void sleep$be(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        BeCensus.blockEntityAt((Level) (Object) this, pos);
    }

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"))
    private void sleep$neighbors(BlockPos pos, Block block, CallbackInfo ci) {
        BeCensus.mark(BeCensus.NEIGHBORS);
    }

}
