package dev.lull.opt;

import dev.lull.optsupport.SlicePool;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * lab.opt.NoiseSlicePoolMixin: an interpolator is built without slices; NoiseSlicePoolChunkMixin hands it a pair from
 * the thread's SlicePool when its NoiseChunk starts interpolating and takes them back when it stops (see SlicePool).
 */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseSlicePoolMixin implements SlicePool.Slices {
    @Shadow double[][] slice0;
    @Shadow double[][] slice1;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;allocateSlice(II)[[D"))
    private double[][] sleep$noSlice(NoiseChunk.NoiseInterpolator self, int cellCountY, int cellCountXZ) {
        return null;
    }

    @Override
    public void sleep$take(SlicePool pool, int rows, int cols) {
        if (slice0 == null) slice0 = pool.take(rows, cols);
        if (slice1 == null) slice1 = pool.take(rows, cols);
    }

    @Override
    public void sleep$give(SlicePool pool) {
        pool.give(slice0);
        pool.give(slice1);
        slice0 = null;
        slice1 = null;
    }
}
