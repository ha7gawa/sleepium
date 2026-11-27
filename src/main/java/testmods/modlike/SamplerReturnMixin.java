package testmods.modlike;

import java.util.List;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import testmods.modlikestate.SamplerSeedCheck;

/** Issue #17 test probe (-Dsleep.test.samplerReturn): mirrors Fabric Biome API's RETURN inject on NoiseChunk.cachedClimateSampler. */
@Mixin(NoiseChunk.class)
public abstract class SamplerReturnMixin {
    @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
    private void sleep$testMarkSampler(NoiseRouter router, List<Climate.ParameterPoint> points, CallbackInfoReturnable<Climate.Sampler> cir) {
        if ((Object) cir.getReturnValue() instanceof SamplerSeedCheck sc) sc.sleep$markSeed();
    }
}
