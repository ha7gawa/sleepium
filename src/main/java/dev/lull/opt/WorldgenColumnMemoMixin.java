package dev.lull.opt;

import dev.lull.optsupport.ColumnMemo;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * exp/worldgen-column-memo: climate values that do not depend on height computed once per column of a chunk instead of
 * for every 4x4x4 cell up the column (datapacks without flat_cache markers - JJThunder To The Max: generation ~2x faster;
 * vanilla's own worldgen is marked already, unchanged). ColumnMemo: the router's y-free subtrees marked, each mark made a
 * memo of the chunk's columns where vanilla turns markers into its caches.
 *
 * <p>Swaps only the NoiseRouter argument instead of cancelling cachedClimateSampler, so other mods' RETURN injections
 * (Fabric Biome API's End biome sampler seed via Connector, issue #17) still run on the returned Climate.Sampler.
 */
@Mixin(NoiseChunk.class)
public abstract class WorldgenColumnMemoMixin {
    @ModifyVariable(method = "cachedClimateSampler", at = @At("HEAD"), argsOnly = true)
    private NoiseRouter sleep$columnSampler(NoiseRouter router) {
        return ColumnMemo.router(router);
    }

    @Inject(method = "wrapNew", at = @At("HEAD"), cancellable = true)
    private void sleep$columnMemo(DensityFunction df, CallbackInfoReturnable<DensityFunction> cir) {
        if (df instanceof ColumnMemo.Mark m) cir.setReturnValue(new ColumnMemo.Memo(m.wrapped));
    }
}
