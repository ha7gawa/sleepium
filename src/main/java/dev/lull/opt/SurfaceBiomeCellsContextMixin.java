package dev.lull.opt;

import dev.lull.optsupport.SurfaceCells;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SurfaceBiomeCells: a context made inside the chunk's buildSurface looks its biomes up through the chunk's cell table. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public abstract class SurfaceBiomeCellsContextMixin {
    @Shadow @Final @Mutable private Function<BlockPos, Holder<Biome>> biomeGetter;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$cells(SurfaceSystem system, RandomState randomState, ChunkAccess chunk, NoiseChunk noiseChunk,
                             Function<BlockPos, Holder<Biome>> biomeGetter, Registry<Biome> biomes, WorldGenerationContext context, CallbackInfo ci) {
        this.biomeGetter = SurfaceCells.getter(chunk, this.biomeGetter);
    }
}
