package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of SpecTicks (lab): during a run the shared path type cache is read-only; each thread computes into its overlay. */
@Mixin(PathTypeCache.class)
public abstract class ServerParallelItemsPathMixin {
    @Shadow @Final private long[] positions;
    @Shadow @Final private PathType[] pathTypes;

    @Inject(method = "getOrCompute", at = @At("HEAD"), cancellable = true)
    private void sleep$specPath(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        if (dev.lull.optsupport.SpecTicks.running()) cir.setReturnValue(dev.lull.optsupport.SpecTicks.pathType(positions, pathTypes, level, pos));
    }
}
