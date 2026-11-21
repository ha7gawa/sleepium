package dev.lull.optsupport;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * SurfaceBiomeCells: the surface step's biome lookups (a biome condition of the surface rules, once per block it reaches)
 * answered per quart cell where the cell is uniform.
 *
 * <p>BiomeManager.getBiome(pos) returns source.getNoiseBiome(corner) for one of the 8 corners of the quart cell
 * ((x - 2) >> 2, (y - 2) >> 2, (z - 2) >> 2) and their +1 neighbours; which corner depends on the block's position in
 * the cell and the seed's fiddle offsets. When the 8 corners give the same Holder, every block of the cell gets that
 * Holder whichever corner is nearest: it is kept per cell and returned without the distances. A cell whose corners
 * differ ("mixed") goes the vanilla way for every block.
 *
 * <p>The corner lookups are the calls vanilla makes (the region's getNoiseBiome: the chunk's stored biomes). Only during
 * the SURFACE step of the chunk at the region's centre, for blocks of that chunk: the corners are then at most one chunk
 * away, where SURFACE requires BIOMES (the stored biomes are final and the lookup cannot fail).
 */
public final class SurfaceCells implements Function<BlockPos, Holder<Biome>> {
    private static final ThreadLocal<SurfaceCells> CURRENT = new ThreadLocal<>();
    private static final Object MIXED = new Object();

    private final BiomeManager manager;
    private final BiomeManager.NoiseBiomeSource source;
    private final ChunkAccess chunk;
    /** cell coordinates of the table's first cell */
    private final int l0, i0, j0, ny, minY, maxY;
    /** per cell (x 0..4, z 0..4, y): null unknown, MIXED, or the cell's one Holder */
    private final Object[] cells;

    private SurfaceCells(BiomeManager manager, BiomeManager.NoiseBiomeSource source, ChunkAccess chunk) {
        this.manager = manager;
        this.source = source;
        this.chunk = chunk;
        this.l0 = ((chunk.getPos().getMinBlockX() - 2) >> 2);
        this.j0 = ((chunk.getPos().getMinBlockZ() - 2) >> 2);
        this.minY = chunk.getMinBuildHeight();
        this.maxY = chunk.getMaxBuildHeight();
        this.i0 = (minY - 2) >> 2;
        this.ny = ((maxY - 1 - 2) >> 2) - i0 + 1;
        this.cells = new Object[5 * 5 * ny];
    }

    /** SurfaceSystem.buildSurface starts: a table for this chunk if its region is the chunk's SURFACE step */
    public static void begin(BiomeManager manager, ChunkAccess chunk, ChunkStatus regionStep) {
        BiomeManager.NoiseBiomeSource src = ((dev.lull.opt.BiomeManagerSourceAccessor) manager).sleep$noiseSource();
        CURRENT.set(src instanceof WorldGenRegion r && regionStep == ChunkStatus.SURFACE && r.getCenter().equals(chunk.getPos())
                ? new SurfaceCells(manager, src, chunk) : null);
    }

    public static void end() {
        CURRENT.set(null);
    }

    /** the biome getter for a SurfaceRules.Context made now for this chunk */
    public static Function<BlockPos, Holder<Biome>> getter(ChunkAccess chunk, Function<BlockPos, Holder<Biome>> vanilla) {
        SurfaceCells c = CURRENT.get();
        return c != null && c.chunk == chunk ? c : vanilla;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Holder<Biome> apply(BlockPos pos) {
        int y = pos.getY();
        if (y >= minY && y < maxY) {
            int l = (pos.getX() - 2) >> 2, i1 = (y - 2) >> 2, j1 = (pos.getZ() - 2) >> 2;
            int lx = l - l0, lz = j1 - j0;
            if (lx >= 0 && lx < 5 && lz >= 0 && lz < 5) {
                int idx = ((lx * 5) + lz) * ny + (i1 - i0);
                Object o = cells[idx];
                if (o == null) cells[idx] = o = uniform(l, i1, j1);
                if (o != MIXED) return (Holder<Biome>) o;
            }
        }
        return manager.getBiome(pos);
    }

    private Object uniform(int l, int i1, int j1) {
        Holder<Biome> first = source.getNoiseBiome(l, i1, j1);
        for (int c = 1; c < 8; c++) {
            Holder<Biome> h = source.getNoiseBiome((c & 4) == 0 ? l : l + 1, (c & 2) == 0 ? i1 : i1 + 1, (c & 1) == 0 ? j1 : j1 + 1);
            if (h != first) return MIXED;
        }
        return first;
    }
}
