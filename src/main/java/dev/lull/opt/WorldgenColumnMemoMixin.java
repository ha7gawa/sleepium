package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.lull.optsupport.ColumnMemo;
import java.util.List;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * exp/worldgen-column-memo: climate values that do not depend on height computed once per column of a chunk instead of
 * for every 4x4x4 cell up the column (datapacks without flat_cache markers - JJThunder To The Max: generation ~2x faster;
 * vanilla's own worldgen is marked already, unchanged). ColumnMemo: the router's y-free subtrees marked, each mark made a
 * memo of the chunk's columns where vanilla turns markers into its caches.
 */
@Mixin(NoiseChunk.class)
public abstract class WorldgenColumnMemoMixin {
    @Shadow
    protected abstract DensityFunction wrap(DensityFunction densityFunction);

    @Inject(method = "cachedClimateSampler", at = @At("HEAD"), cancellable = true)
    private void sleep$columnSampler(NoiseRouter router, List<Climate.ParameterPoint> points, CallbackInfoReturnable<Climate.Sampler> cir) {
        DensityFunction[] f = ColumnMemo.climate(router);
        cir.setReturnValue(new Climate.Sampler(f[0].mapAll(this::wrap), f[1].mapAll(this::wrap), f[2].mapAll(this::wrap), f[3].mapAll(this::wrap),
                f[4].mapAll(this::wrap), f[5].mapAll(this::wrap), points));
        dev.lull.optsupport.MapAllMemo.end();   // WorldgenMapMemo: the memo keeps nothing of this chunk
    }

    /**
     * a Mark (wrapNew returns it unchanged) becomes the chunk's Memo. (11-21: a
     * return-value modifier instead of a cancellable HEAD inject, which made a CallbackInfoReturnable for every new key
     * of every NoiseChunk - ~0.4 % of generation's allocation; the same result for every input.)
     */
    @ModifyReturnValue(method = "wrapNew", at = @At("RETURN"))
    private DensityFunction sleep$columnMemo(DensityFunction out, @Local(argsOnly = true) DensityFunction df) {
        return df instanceof ColumnMemo.Mark m ? new ColumnMemo.Memo(m.wrapped) : out;
    }
}
