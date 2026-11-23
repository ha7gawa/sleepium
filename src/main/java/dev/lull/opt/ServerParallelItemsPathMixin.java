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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of SpecTicks (lab): during a run the shared path type cache is read-only; each thread computes into its overlay. */
@Mixin(PathTypeCache.class)
public abstract class ServerParallelItemsPathMixin {
    @Shadow @Final @org.spongepowered.asm.mixin.Mutable private long[] positions;
    @Shadow @Final @org.spongepowered.asm.mixin.Mutable private PathType[] pathTypes;

    /** lab.fast: a larger cache (SpecTicks.PATH_MASK + 1 entries; vanilla's 4096 evicts constantly with many mobs) */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$fastSize(CallbackInfo ci) {
        if (dev.lull.optsupport.SpecTicks.PATH_MASK != 4095) {
            positions = new long[dev.lull.optsupport.SpecTicks.PATH_MASK + 1];
            pathTypes = new PathType[dev.lull.optsupport.SpecTicks.PATH_MASK + 1];
        }
    }

    @org.spongepowered.asm.mixin.injection.ModifyConstant(method = "index", constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 4095))
    private static int sleep$fastMask(int mask) {
        return dev.lull.optsupport.SpecTicks.PATH_MASK;
    }

    @Inject(method = "getOrCompute", at = @At("HEAD"), cancellable = true)
    private void sleep$specPath(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        if (dev.lull.optsupport.SpecTicks.running()) cir.setReturnValue(dev.lull.optsupport.SpecTicks.pathType(positions, pathTypes, level, pos));
    }
}
