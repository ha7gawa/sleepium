package testmods.modlike;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import testmods.modlikestate.SamplerSeedCheck;

/** Issue #17 test probe (-Dsleep.test.samplerReturn): mirrors Fabric Biome API's added seed field on Climate.Sampler. */
@Mixin(Climate.Sampler.class)
public abstract class SamplerSeedMixin implements SamplerSeedCheck {
    @Unique private boolean sleep$testSeed;

    @Override
    public void sleep$markSeed() {
        this.sleep$testSeed = true;
    }

    @Override
    public boolean sleep$hasSeed() {
        return this.sleep$testSeed;
    }
}
