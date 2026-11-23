package dev.lull.mixin;

import dev.lull.TickCensus;
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

/** Measurement only (-Dsleep.tickCensus=true): vanilla's shared path type cache as a parallel entity run would see it. */
@Mixin(PathTypeCache.class)
public abstract class TickCensusPathMixin {
    @Shadow @Final private long[] positions;
    @Shadow @Final private PathType[] pathTypes;

    @Inject(method = "getOrCompute", at = @At("HEAD"))
    private void sleep$censusPath(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        TickCensus.pathLookup(positions, pathTypes, level, pos);
    }
}
