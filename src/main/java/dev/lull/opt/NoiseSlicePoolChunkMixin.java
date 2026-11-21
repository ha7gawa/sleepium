package dev.lull.opt;

import dev.lull.optsupport.SlicePool;
import java.util.List;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * lab.opt.NoiseSlicePoolMixin (part): the interpolators take their slices from the thread's pool after vanilla's
 * "started twice" check, right before the first slice is filled, and give them back when interpolation stops (see
 * SlicePool).
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseSlicePoolChunkMixin {
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final int cellCountXZ;
    @Shadow @Final int cellCountY;

    @Inject(method = "initializeForFirstCellX", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;fillSlice(ZI)V"))
    private void sleep$takeSlices(CallbackInfo ci) {
        SlicePool pool = SlicePool.get();
        for (int i = 0, n = interpolators.size(); i < n; i++)
            ((SlicePool.Slices) interpolators.get(i)).sleep$take(pool, cellCountXZ + 1, cellCountY + 1);
    }

    @Inject(method = "stopInterpolation", at = @At("RETURN"))
    private void sleep$giveSlices(CallbackInfo ci) {
        SlicePool pool = SlicePool.get();
        for (int i = 0, n = interpolators.size(); i < n; i++) ((SlicePool.Slices) interpolators.get(i)).sleep$give(pool);
    }
}
