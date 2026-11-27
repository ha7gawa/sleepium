package testmods.modlikestate;

/** Issue #17 test probe (-Dsleep.test.samplerReturn): interface added to Climate.Sampler to mirror Fabric Biome API's seed field. */
public interface SamplerSeedCheck {
    void sleep$markSeed();

    boolean sleep$hasSeed();
}
