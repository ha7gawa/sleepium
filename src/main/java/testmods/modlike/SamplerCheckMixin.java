package testmods.modlike;

import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import testmods.modlikestate.SamplerSeedCheck;

/** Issue #17 test probe (-Dsleep.test.samplerReturn): fails like Fabric Biome API if cachedClimateSampler's RETURN inject was bypassed. */
@Mixin(LevelChunkSection.class)
public abstract class SamplerCheckMixin {
    @org.spongepowered.asm.mixin.Unique private static boolean sleep$logged;

    @Inject(method = "fillBiomesFromNoise", at = @At("HEAD"))
    private void sleep$testCheckSampler(BiomeResolver resolver, Climate.Sampler sampler, int x, int y, int z, CallbackInfo ci) {
        if ((Object) sampler instanceof SamplerSeedCheck sc) {
            if (!sc.sleep$hasSeed()) {
                throw new IllegalStateException("MultiNoiseSampler doesn't have a seed set (sleep.test.samplerReturn)");
            }
            if (!sleep$logged) {
                sleep$logged = true;
                System.out.println("[sleep/test] samplerReturn: sampler seed set via cachedClimateSampler RETURN");
            }
        }
    }
}
