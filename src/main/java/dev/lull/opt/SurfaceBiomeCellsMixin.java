package dev.lull.opt;

import dev.lull.optsupport.SurfaceCells;
import net.minecraft.core.Registry;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SurfaceBiomeCells: a uniform-cell biome table for the surface rules of one chunk (see SurfaceCells); once per chunk. */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceBiomeCellsMixin {
    @Inject(method = "buildSurface", at = @At("HEAD"))
    private void sleep$cellsBegin(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean useLegacyRandomSource,
                                  WorldGenerationContext context, ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource,
                                  CallbackInfo ci) {
        ChunkStatus step = ((BiomeManagerSourceAccessor) biomeManager).sleep$noiseSource() instanceof WorldGenRegion r
                ? ((RegionStepAccessor) r).sleep$status() : null;
        SurfaceCells.begin(biomeManager, chunk, step);
    }

    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void sleep$cellsEnd(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean useLegacyRandomSource,
                                WorldGenerationContext context, ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource,
                                CallbackInfo ci) {
        SurfaceCells.end();
    }
}
