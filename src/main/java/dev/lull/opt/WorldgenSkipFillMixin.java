package dev.lull.opt;

import dev.lull.optsupport.CellProof;
import dev.lull.optsupport.FillWriter;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * WorldgenSkip (cells): vanilla's doFill, plus one check before each noise cell. If CellProof shows the final density is
 * below zero at every block of the cell and the aquifer answers exactly AIR for every block of it, every block of the
 * cell would come out AIR — which doFill does not write — so the cell's 128 evaluations are skipped. If it shows the
 * density is above zero everywhere (the aquifer returns null) and the ore filler provably returns null too, every block
 * is the default block: written in vanilla's order with vanilla's heightmap updates, without the evaluations. Nothing else
 * changes: the per-cell interpolator state is reset by the next cell's selectCellYZ; caches not filled here are filled
 * on demand with the same values; the aquifer flag is only read right after a computeSubstance.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class WorldgenSkipFillMixin {
    @Shadow @Final private static BlockState AIR;
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    private NoiseChunk createNoiseChunk(ChunkAccess chunk, StructureManager structureManager, Blender blender, RandomState random) {
        throw new AssertionError();
    }

    @Shadow
    private BlockState debugPreliminarySurfaceLevel(NoiseChunk chunk, int x, int y, int z, BlockState state) {
        throw new AssertionError();
    }

    /**
     * @author probe
     * @reason skip noise cells proved to be all AIR (see class doc); otherwise vanilla, line by line
     */
    @Overwrite
    private ChunkAccess doFill(Blender blender, StructureManager structureManager, RandomState random, ChunkAccess chunk, int minCellY, int cellCountY) {
        NoiseChunk noisechunk = chunk.getOrCreateNoiseChunk(p_224255_ -> this.createNoiseChunk(p_224255_, structureManager, blender, random));
        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkpos = chunk.getPos();
        int i = chunkpos.getMinBlockX();
        int j = chunkpos.getMinBlockZ();
        Aquifer aquifer = noisechunk.aquifer();
        noisechunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        int k = ((NoiseChunkStateInvoker) noisechunk).sleep$cellWidth();
        int l = ((NoiseChunkStateInvoker) noisechunk).sleep$cellHeight();
        int i1 = 16 / k;
        int j1 = 16 / k;
        CellProof.Host host = (CellProof.Host) noisechunk;
        CellProof proof = host.sleep$proof();
        CellProof.AirBox airBox = aquifer instanceof CellProof.AirBox a ? a : null;
        // aquifers that return null for substance > 0 without other effects: ours (NoiseBasedAquifer) and the disabled one
        boolean solidAquifer = airBox != null || (aquifer.getClass().isAnonymousClass() && aquifer.getClass().getNestHost() == Aquifer.class);
        CellProof.Solid solidHook = aquifer instanceof CellProof.Solid sh ? sh : null;
        CellProof.Cell cell = proof != null ? new CellProof.Cell() : null;
        // FillWrite (lab): the writes below into the sections' packed storage (see FillWriter)
        FillWriter fw = FillWriter.ON ? FillWriter.get() : null;
        if (fw != null) fw.begin(chunk, chunk.getSectionsCount(), heightmap, Heightmap.Types.OCEAN_FLOOR_WG, heightmap1, Heightmap.Types.WORLD_SURFACE_WG);
        try {

        for (int k1 = 0; k1 < i1; k1++) {
            noisechunk.advanceCellX(k1);

            for (int l1 = 0; l1 < j1; l1++) {
                int i2 = chunk.getSectionsCount() - 1;
                LevelChunkSection levelchunksection = chunk.getSection(i2);

                for (int j2 = cellCountY - 1; j2 >= 0; j2--) {
                    if (proof != null) {
                        cell.yIndex = j2;
                        cell.zIndex = l1;
                        cell.x0 = i + k1 * k;
                        cell.x1 = cell.x0 + k - 1;
                        cell.z0 = j + l1 * k;
                        cell.z1 = cell.z0 + k - 1;
                        cell.y0 = (minCellY + j2) * l;
                        cell.y1 = cell.y0 + l - 1;
                        if (proof.allNegative(cell)) {
                            if (airBox != null && airBox.sleep$boxAir(cell.x0, cell.y0, cell.z0, cell.x1, cell.y1, cell.z1)) continue;
                        } else if (solidAquifer && proof.lastAllPositive() && host.sleep$solidIsDefault(cell)) {
                            // every block: aquifer null (density > 0), ore null → the default block; vanilla's writes, in order
                            BlockState def = this.settings.value().defaultBlock();
                            if (solidHook != null) solidHook.sleep$solid();
                            for (int k2 = l - 1; k2 >= 0; k2--) {
                                int l2 = (minCellY + j2) * l + k2;
                                int i3 = l2 & 15;
                                int j3 = chunk.getSectionIndex(l2);
                                if (i2 != j3) {
                                    i2 = j3;
                                    levelchunksection = chunk.getSection(j3);
                                }
                                for (int k3 = 0; k3 < k; k3++) {
                                    int l3 = i + k1 * k + k3;
                                    int i4 = l3 & 15;
                                    for (int j4 = 0; j4 < k; j4++) {
                                        int k4 = j + l1 * k + j4;
                                        int l4 = k4 & 15;
                                        BlockState blockstate = this.debugPreliminarySurfaceLevel(noisechunk, l3, l2, k4, def);
                                        if (blockstate != AIR && !SharedConstants.debugVoidTerrain(chunk.getPos())) {
                                            if (fw != null) {
                                                fw.set(i2, levelchunksection, i4, i3, l4, blockstate);
                                                fw.heights(i4, l2, l4, blockstate);
                                            } else {
                                                levelchunksection.setBlockState(i4, i3, l4, blockstate, false);
                                                heightmap.update(i4, l2, l4, blockstate);
                                                heightmap1.update(i4, l2, l4, blockstate);
                                            }
                                            if (aquifer.shouldScheduleFluidUpdate() && !blockstate.getFluidState().isEmpty()) {
                                                blockpos$mutableblockpos.set(l3, l2, k4);
                                                chunk.markPosForPostprocessing(blockpos$mutableblockpos);
                                            }
                                        }
                                    }
                                }
                            }
                            continue;
                        }
                    }
                    noisechunk.selectCellYZ(j2, l1);

                    for (int k2 = l - 1; k2 >= 0; k2--) {
                        int l2 = (minCellY + j2) * l + k2;
                        int i3 = l2 & 15;
                        int j3 = chunk.getSectionIndex(l2);
                        if (i2 != j3) {
                            i2 = j3;
                            levelchunksection = chunk.getSection(j3);
                        }

                        double d0 = (double) k2 / (double) l;
                        noisechunk.updateForY(l2, d0);

                        for (int k3 = 0; k3 < k; k3++) {
                            int l3 = i + k1 * k + k3;
                            int i4 = l3 & 15;
                            double d1 = (double) k3 / (double) k;
                            noisechunk.updateForX(l3, d1);

                            for (int j4 = 0; j4 < k; j4++) {
                                int k4 = j + l1 * k + j4;
                                int l4 = k4 & 15;
                                double d2 = (double) j4 / (double) k;
                                noisechunk.updateForZ(k4, d2);
                                BlockState blockstate = ((NoiseChunkStateInvoker) noisechunk).sleep$getInterpolatedState();
                                if (blockstate == null) {
                                    blockstate = this.settings.value().defaultBlock();
                                }

                                blockstate = this.debugPreliminarySurfaceLevel(noisechunk, l3, l2, k4, blockstate);
                                if (blockstate != AIR && !SharedConstants.debugVoidTerrain(chunk.getPos())) {
                                    if (fw != null) {
                                        fw.set(i2, levelchunksection, i4, i3, l4, blockstate);
                                        fw.heights(i4, l2, l4, blockstate);
                                    } else {
                                        levelchunksection.setBlockState(i4, i3, l4, blockstate, false);
                                        heightmap.update(i4, l2, l4, blockstate);
                                        heightmap1.update(i4, l2, l4, blockstate);
                                    }
                                    if (aquifer.shouldScheduleFluidUpdate() && !blockstate.getFluidState().isEmpty()) {
                                        blockpos$mutableblockpos.set(l3, l2, k4);
                                        chunk.markPosForPostprocessing(blockpos$mutableblockpos);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            noisechunk.swapSlices();
        }

        } finally {
            if (fw != null) fw.end();
        }
        noisechunk.stopInterpolation();
        return chunk;
    }
}
