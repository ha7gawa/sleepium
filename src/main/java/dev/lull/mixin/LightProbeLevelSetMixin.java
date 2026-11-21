package dev.lull.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measurement only (-Dlab.light.trace=box, notes/84): block changes in the traced box (old -> new, the chunk's status, the thread). */
@Mixin(LevelChunk.class)
public abstract class LightProbeLevelSetMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void sleep$set(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
        if (!dev.lull.LightTrace.in(pos)) return;
        LevelChunk self = (LevelChunk) (Object) this;
        BlockState old = self.getBlockState(pos);
        if (old != state) dev.lull.LightTrace.log("set " + pos.toShortString() + " " + old + " -> " + state + " (LevelChunk " + self.getPersistedStatus() + ")");
    }
}
