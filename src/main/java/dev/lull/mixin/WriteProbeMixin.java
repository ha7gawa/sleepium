package dev.lull.mixin;

import dev.lull.BeTickStats;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World-visible write hooks for the idle-tick detector ({@link BeTickStats#writes}). The other hooks live in
 * WriteProbeEntityMixin, WriteProbeBlockEntityMixin, EventSourcesMixin and LevelTicksSourcesMixin.
 */
@Mixin(Level.class)
public abstract class WriteProbeMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void sleep$setBlock(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        BeTickStats.write();
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.onPos((Level) (Object) this, pos, "block");
    }
}
