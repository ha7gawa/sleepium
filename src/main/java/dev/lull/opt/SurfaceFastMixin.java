package dev.lull.opt;

import dev.lull.optsupport.SurfaceWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * SurfaceFast (lab, -Dlab.opt.SurfaceFastMixin): buildSurface's column loop over a snapshot of the column with lean
 * writes (see SurfaceWriter); vanilla's loop, line by line, for chunks it does not take and as the reference of
 * -Dlab.surface.verify. The rule source's apply, the context's construction and the extensions are vanilla's calls in
 * vanilla's order (SurfacePlanMixin, SurfaceBiomeCells, SurfaceFluidEmpty still hook them).
 */
@Mixin(value = SurfaceSystem.class, priority = 900)
public abstract class SurfaceFastMixin {
    @Shadow @Final private BlockState defaultBlock;

    @Shadow
    private void erodedBadlandsExtension(BlockColumn blockColumn, int x, int z, int height, LevelHeightAccessor level) {
        throw new AssertionError();
    }

    @Shadow
    private void frozenOceanExtension(int minSurfaceLevel, Biome biome, BlockColumn blockColumn, BlockPos.MutableBlockPos topWaterPos, int x, int z, int height) {
        throw new AssertionError();
    }

    @Shadow
    private boolean isStone(BlockState state) {
        throw new AssertionError();
    }

    /**
     * @author probe
     * @reason SurfaceFast: the column loop over a snapshot (SurfaceWriter); vanilla's loop otherwise
     */
    @Overwrite
    public void buildSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean useLegacyRandomSource,
                             WorldGenerationContext context, ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkPos chunkpos = chunk.getPos();
        int i = chunkpos.getMinBlockX();
        int j = chunkpos.getMinBlockZ();
        BlockColumn blockcolumn = new SurfaceWriter.Column(chunk, pos);
        SurfaceRules.Context ctx = new SurfaceRules.Context((SurfaceSystem) (Object) this, randomState, chunk, noiseChunk, biomeManager::getBiome, biomes, context);
        SurfaceRules.SurfaceRule rule = ruleSource.apply(ctx);
        SurfaceWriter w = SurfaceWriter.get();
        boolean fast = w.begin(chunk, rule, this.defaultBlock);
        if (!fast) SurfaceWriter.skipped();
        try {
            if (fast && SurfaceWriter.VERIFY) {
                SurfaceWriter.Saved before = SurfaceWriter.save(chunk);
                this.sleep$loop(false, w, biomeManager, useLegacyRandomSource, chunk, i, j, pos, blockcolumn, ctx, rule);
                SurfaceWriter.Saved ref = SurfaceWriter.save(chunk);
                SurfaceWriter.restore(chunk, before);
                SurfaceRules.Context ctx2 = new SurfaceRules.Context((SurfaceSystem) (Object) this, randomState, chunk, noiseChunk, biomeManager::getBiome, biomes, context);
                SurfaceRules.SurfaceRule rule2 = ruleSource.apply(ctx2);
                w.begin(chunk, rule2, this.defaultBlock);
                this.sleep$loop(true, w, biomeManager, useLegacyRandomSource, chunk, i, j, pos, blockcolumn, ctx2, rule2);
                SurfaceWriter.compare(chunk, ref);
            } else {
                this.sleep$loop(fast, w, biomeManager, useLegacyRandomSource, chunk, i, j, pos, blockcolumn, ctx, rule);
            }
        } finally {
            w.end();
        }
    }

    @Unique
    private void sleep$loop(boolean fast, SurfaceWriter w, BiomeManager biomeManager, boolean useLegacyRandomSource, ChunkAccess chunk, int i, int j,
                            BlockPos.MutableBlockPos blockpos$mutableblockpos, BlockColumn blockcolumn, SurfaceRules.Context surfacerules$context,
                            SurfaceRules.SurfaceRule surfacerules$surfacerule) {
        BlockPos.MutableBlockPos blockpos$mutableblockpos1 = new BlockPos.MutableBlockPos();

        for (int k = 0; k < 16; k++) {
            for (int l = 0; l < 16; l++) {
                int i1 = i + k;
                int j1 = j + l;
                int k1 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, k, l) + 1;
                blockpos$mutableblockpos.setX(i1).setZ(j1);
                Holder<Biome> holder = biomeManager.getBiome(blockpos$mutableblockpos1.set(i1, useLegacyRandomSource ? 0 : k1, j1));
                if (holder.is(Biomes.ERODED_BADLANDS)) {
                    this.erodedBadlandsExtension(blockcolumn, i1, j1, k1, chunk);
                }

                int l1 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, k, l) + 1;
                surfacerules$context.updateXZ(i1, j1);
                if (fast) {
                    w.column(k, l, l1, i1, j1, surfacerules$context, surfacerules$surfacerule);
                } else {
                    int i2 = 0;
                    int j2 = Integer.MIN_VALUE;
                    int k2 = Integer.MAX_VALUE;
                    int l2 = chunk.getMinBuildHeight();

                    for (int i3 = l1; i3 >= l2; i3--) {
                        BlockState blockstate = blockcolumn.getBlock(i3);
                        if (blockstate.isAir()) {
                            i2 = 0;
                            j2 = Integer.MIN_VALUE;
                        } else if (!blockstate.getFluidState().isEmpty()) {
                            if (j2 == Integer.MIN_VALUE) {
                                j2 = i3 + 1;
                            }
                        } else {
                            if (k2 >= i3) {
                                k2 = DimensionType.WAY_BELOW_MIN_Y;

                                for (int j3 = i3 - 1; j3 >= l2 - 1; j3--) {
                                    BlockState blockstate1 = blockcolumn.getBlock(j3);
                                    if (!this.isStone(blockstate1)) {
                                        k2 = j3 + 1;
                                        break;
                                    }
                                }
                            }

                            i2++;
                            int k3 = i3 - k2 + 1;
                            surfacerules$context.updateY(i2, k3, j2, i1, i3, j1);
                            if (blockstate == this.defaultBlock) {
                                BlockState blockstate2 = surfacerules$surfacerule.tryApply(i1, i3, j1);
                                if (blockstate2 != null) {
                                    blockcolumn.setBlock(i3, blockstate2);
                                }
                            }
                        }
                    }
                }

                if (holder.is(Biomes.FROZEN_OCEAN) || holder.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    this.frozenOceanExtension(surfacerules$context.getMinSurfaceLevel(), holder.value(), blockcolumn, blockpos$mutableblockpos1, i1, j1, k1);
                }
            }
        }
    }
}
