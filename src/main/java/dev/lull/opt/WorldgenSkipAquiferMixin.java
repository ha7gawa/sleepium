package dev.lull.opt;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * WorldgenSkip: NoiseBasedAquifer.computeSubstance runs for every non-solid block of NOISE (and for carved blocks);
 * two thirds of all blocks, mostly the air above the surface, take its slow path: find the 3 nearest of 12 aquifer
 * grid points, their fluid statuses, pressures between them — to answer "air".
 *
 * <p>Exact shortcut: the 12 candidate grid points depend only on the block's base grid cell
 * (floorDiv(x - 5, 16), floorDiv(y + 1, 12), floorDiv(z - 5, 16)). If all 12 statuses are equal (same level, same
 * fluid) — above the surface all are the global sea-level status — then whichever are nearest:
 * the block state is S.at(y); calculatePressure(S, S) is exactly 0.0 (equal levels), so no barrier can make the block
 * solid; so the result is S.at(y) unless it is water over global lava (then the vanilla path runs). The one thing
 * that depends on the distances is shouldScheduleFluidUpdate (d1 ≤ 0 ? d1 ≥ FLOWING_UPDATE_SIMULARITY : true): it is
 * computed from the same distances when someone asks for it (before the next computeSubstance).
 *
 * <p>Uniformity is decided once per base cell from the 12 cached locations; a status is taken only where it is cheap
 * and exact (already cached, or computeFluid's early "above the surface" return), otherwise the cell is left to the
 * vanilla path, so no status is computed that vanilla would not compute.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class WorldgenSkipAquiferMixin implements dev.lull.optsupport.CellProof.AirBox, dev.lull.optsupport.CellProof.Solid {
    @Shadow @Final private static double FLOWING_UPDATE_SIMULARITY;
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final protected Aquifer.FluidStatus[] aquiferCache;
    @Shadow @Final protected long[] aquiferLocationCache;
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow protected boolean shouldScheduleFluidUpdate;

    @Shadow @Final private net.minecraft.world.level.levelgen.NoiseChunk noiseChunk;

    @Shadow
    protected abstract int getIndex(int gridX, int gridY, int gridZ);

    @Shadow
    protected abstract int gridX(int x);

    @Shadow
    protected abstract int gridY(int y);

    @Shadow
    protected abstract int gridZ(int z);

    @Shadow
    private Aquifer.FluidStatus getAquiferStatus(long packedPos) {
        throw new AssertionError();
    }

    @Shadow
    private double calculatePressure(DensityFunction.FunctionContext context, MutableDouble substance, Aquifer.FluidStatus firstFluid,
                                     Aquifer.FluidStatus secondFluid) {
        throw new AssertionError();
    }

    @Shadow
    protected static double similarity(int firstDistance, int secondDistance) {
        throw new AssertionError();
    }

    /** per base cell (index as aquiferCache): 0 unknown, 1 uniform (status in sleep$uniS), 2 not uniform */
    @Unique private byte[] sleep$uni;
    @Unique private Aquifer.FluidStatus[] sleep$uniS;
    /** the fast path left shouldScheduleFluidUpdate to be computed for this block */
    @Unique private boolean sleep$flagPending;
    @Unique private int sleep$fx, sleep$fy, sleep$fz;

    @Unique
    private long sleep$location(int l3, int i4, int j4) {
        int k4 = this.getIndex(l3, i4, j4);
        long i5 = this.aquiferLocationCache[k4];
        if (i5 != Long.MAX_VALUE) return i5;
        RandomSource randomsource = SLEEP$SCRATCH ? dev.lull.optsupport.ReusableRandom.at(this.positionalRandomFactory, l3, i4, j4, dev.lull.optsupport.ReusableRandom.AQUIFER)
                : this.positionalRandomFactory.at(l3, i4, j4);
        long l4 = BlockPos.asLong(l3 * 16 + randomsource.nextInt(10), i4 * 12 + randomsource.nextInt(9), j4 * 16 + randomsource.nextInt(10));
        this.aquiferLocationCache[k4] = l4;
        return l4;
    }

    /**
     * AquiferCells (-Dlab.opt.AquiferCells): computeSubstance's slow path finds the 3 nearest of its base cell's 12
     * candidate locations for every block; the candidates depend only on the base cell, which consecutive blocks share
     * (a base cell is 16 x 12 x 16 blocks, the fill walks 4 x 8 x 4 noise cells). The last cell's 12 locations are kept
     * unpacked, in vanilla's loop order, made through the same location cache (filled as vanilla's loop fills it): the
     * same distances compared in the same order, the same three picked.
     */
    @Unique private static final boolean SLEEP$CELLS = Boolean.getBoolean("lab.opt.AquiferCells");
    /**
     * AquiferScratch (-Dlab.opt.AquiferScratch, 11-21): the slow path's barrier holder (vanilla: a new MutableDouble(NaN)
     * per call, ~2 % of generation's allocation) is one per aquifer, set to NaN where vanilla makes it - calculatePressure
     * only reads and sets it during the call, and an aquifer serves one chunk's steps one after another on one thread
     * (computeSubstance is not re-entered). A grid point's random location from ReusableRandom's per-thread source
     * (the state the factory's new XoroshiroRandomSource would have; other factories as vanilla).
     */
    @Unique private static final boolean SLEEP$SCRATCH = Boolean.getBoolean("lab.opt.AquiferScratch");
    @Unique private final MutableDouble sleep$substance = new MutableDouble(Double.NaN);
    @Unique private int sleep$ml = Integer.MIN_VALUE, sleep$mi, sleep$mj;
    @Unique private final int[] sleep$mxyz = new int[36];
    @Unique private final long[] sleep$mloc = new long[12];

    @Unique
    private void sleep$cell(int l, int i1, int j1) {
        int c = 0;
        for (int i3 = 0; i3 <= 1; i3++)
            for (int j3 = -1; j3 <= 1; j3++)
                for (int k3 = 0; k3 <= 1; k3++) {
                    long l4 = sleep$location(l + i3, i1 + j3, j1 + k3);
                    sleep$mloc[c] = l4;
                    sleep$mxyz[c * 3] = BlockPos.getX(l4);
                    sleep$mxyz[c * 3 + 1] = BlockPos.getY(l4);
                    sleep$mxyz[c * 3 + 2] = BlockPos.getZ(l4);
                    c++;
                }
        sleep$ml = l;
        sleep$mi = i1;
        sleep$mj = j1;
    }

    /**
     * The status vanilla's getAquiferStatus would return for this location, if it is cheap to know: already cached, or
     * computeFluid's exact early return (the location is more than 20 blocks above its own column's preliminary
     * surface: the first sampling offset is (0, 0) and returns the global status). Otherwise null (not decided here).
     */
    @Unique
    @Nullable
    private Aquifer.FluidStatus sleep$cheapStatus(long loc) {
        int x = BlockPos.getX(loc), y = BlockPos.getY(loc), z = BlockPos.getZ(loc);
        // a location outside the grid: its y wrapped in BlockPos' 12 bits (a world reaching past y 2047 - JJThunder To
        // The Max: index -1475 of 1602, the world's creation crashed). Vanilla reads only the nearest location's status
        // and a wrapped one is never the nearest; here the cell is left to vanilla's path
        int idx = this.getIndex(this.gridX(x), this.gridY(y), this.gridZ(z));
        if (idx < 0 || idx >= this.aquiferCache.length) return null;
        Aquifer.FluidStatus cached = this.aquiferCache[idx];
        if (cached != null) return cached;
        int k1 = this.noiseChunk.preliminarySurfaceLevel(x, z) + 8;
        return y - 12 > k1 ? this.globalFluidPicker.computeFluid(x, y, z) : null;
    }

    /** the common status of the 12 candidates of base cell (l, i1, j1), or null if they differ */
    @Unique
    @Nullable
    private Aquifer.FluidStatus sleep$uniform(int l, int i1, int j1) {
        if (sleep$uni == null) {
            sleep$uni = new byte[aquiferCache.length];
            sleep$uniS = new Aquifer.FluidStatus[aquiferCache.length];
        }
        int idx = this.getIndex(l, i1, j1);
        byte st = sleep$uni[idx];
        if (st == 1) return sleep$uniS[idx];
        if (st == 2) return null;
        Aquifer.FluidStatus first = null;
        for (int i3 = 0; i3 <= 1; i3++)
            for (int j3 = -1; j3 <= 1; j3++)
                for (int k3 = 0; k3 <= 1; k3++) {
                    long loc = sleep$location(l + i3, i1 + j3, j1 + k3);
                    // y wrapped in BlockPos' 12 bits (a world past y 2047): vanilla never takes it as the nearest
                    Aquifer.FluidStatus s = Math.floorDiv(BlockPos.getY(loc), 12) == i1 + j3 ? sleep$cheapStatus(loc) : null;
                    if (s == null) {
                        sleep$uni[idx] = 2;
                        return null;
                    }
                    if (first == null) first = s;
                    else if (s != first && (((FluidStatusAccessor) (Object) s).sleep$level() != ((FluidStatusAccessor) (Object) first).sleep$level()
                            || ((FluidStatusAccessor) (Object) s).sleep$type() != ((FluidStatusAccessor) (Object) first).sleep$type())) {
                        sleep$uni[idx] = 2;
                        return null;
                    }
                }
        sleep$uni[idx] = 1;
        sleep$uniS[idx] = first;
        return first;
    }

    /**
     * For CellProof: every block of the box, given substance ≤ 0, would get exactly Blocks.AIR from computeSubstance —
     * no global lava at any block, and every base cell the box touches is uniform with a status giving AIR at all its
     * heights (the vanilla AIR instance: doFill skips exactly that one; another air-like fluid type would be written).
     */
    @Override
    public void sleep$solid() {   // what computeSubstance does for substance > 0
        sleep$flagPending = false;
        this.shouldScheduleFluidUpdate = false;
    }

    @Override
    public boolean sleep$boxAir(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++)
                    if (this.globalFluidPicker.computeFluid(x, y, z).at(y).is(Blocks.LAVA)) return false;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int l = Math.floorDiv(x0 - 5, 16); l <= Math.floorDiv(x1 - 5, 16); l++)
            for (int i1 = Math.floorDiv(y0 + 1, 12); i1 <= Math.floorDiv(y1 + 1, 12); i1++)
                for (int j1 = Math.floorDiv(z0 - 5, 16); j1 <= Math.floorDiv(z1 - 5, 16); j1++) {
                    Aquifer.FluidStatus s = sleep$uniform(l, i1, j1);
                    if (s == null) return false;
                    FluidStatusAccessor a = (FluidStatusAccessor) (Object) s;
                    // at(y) = y < level ? type : AIR
                    if (a.sleep$type() != air && y0 < a.sleep$level()) return false;
                }
        return true;
    }

    /**
     * @author probe
     * @reason exact shortcut when the block's 12 aquifer candidates share one status (see class doc)
     */
    @Overwrite
    @Nullable
    public BlockState computeSubstance(DensityFunction.FunctionContext context, double substance) {
        sleep$flagPending = false;
        int i = context.blockX();
        int j = context.blockY();
        int k = context.blockZ();
        if (substance > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        } else {
            Aquifer.FluidStatus aquifer$fluidstatus = this.globalFluidPicker.computeFluid(i, j, k);
            if (aquifer$fluidstatus.at(j).is(Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = false;
                return Blocks.LAVA.defaultBlockState();
            } else {
                int l = Math.floorDiv(i - 5, 16);
                int i1 = Math.floorDiv(j + 1, 12);
                int j1 = Math.floorDiv(k - 5, 16);
                Aquifer.FluidStatus uni = sleep$uniform(l, i1, j1);
                if (uni != null) {
                    BlockState bs = uni.at(j);
                    if (!bs.is(Blocks.WATER) || !this.globalFluidPicker.computeFluid(i, j - 1, k).at(j - 1).is(Blocks.LAVA)) {
                        sleep$flagPending = true;
                        sleep$fx = i;
                        sleep$fy = j;
                        sleep$fz = k;
                        return bs;
                    }
                }
                int k1 = Integer.MAX_VALUE;
                int l1 = Integer.MAX_VALUE;
                int i2 = Integer.MAX_VALUE;
                long j2 = 0L;
                long k2 = 0L;
                long l2 = 0L;

                if (SLEEP$CELLS) {
                    // AquiferCells: the 12 candidates of this base cell unpacked once per cell (consecutive blocks share it)
                    if (l != sleep$ml || i1 != sleep$mi || j1 != sleep$mj) sleep$cell(l, i1, j1);
                    int[] xyz = sleep$mxyz;
                    long[] locs = sleep$mloc;
                    for (int c = 0; c < 12; c++) {
                        int i6 = xyz[c * 3] - i;
                        int j5 = xyz[c * 3 + 1] - j;
                        int k5 = xyz[c * 3 + 2] - k;
                        int l5 = i6 * i6 + j5 * j5 + k5 * k5;
                        if (k1 >= l5) {
                            l2 = k2;
                            k2 = j2;
                            j2 = locs[c];
                            i2 = l1;
                            l1 = k1;
                            k1 = l5;
                        } else if (l1 >= l5) {
                            l2 = k2;
                            k2 = locs[c];
                            i2 = l1;
                            l1 = l5;
                        } else if (i2 >= l5) {
                            l2 = locs[c];
                            i2 = l5;
                        }
                    }
                } else
                for (int i3 = 0; i3 <= 1; i3++) {
                    for (int j3 = -1; j3 <= 1; j3++) {
                        for (int k3 = 0; k3 <= 1; k3++) {
                            int l3 = l + i3;
                            int i4 = i1 + j3;
                            int j4 = j1 + k3;
                            int k4 = this.getIndex(l3, i4, j4);
                            long i5 = this.aquiferLocationCache[k4];
                            long l4;
                            if (i5 != Long.MAX_VALUE) {
                                l4 = i5;
                            } else {
                                RandomSource randomsource = SLEEP$SCRATCH
                                        ? dev.lull.optsupport.ReusableRandom.at(this.positionalRandomFactory, l3, i4, j4, dev.lull.optsupport.ReusableRandom.AQUIFER)
                                        : this.positionalRandomFactory.at(l3, i4, j4);
                                l4 = BlockPos.asLong(
                                    l3 * 16 + randomsource.nextInt(10), i4 * 12 + randomsource.nextInt(9), j4 * 16 + randomsource.nextInt(10)
                                );
                                this.aquiferLocationCache[k4] = l4;
                            }

                            int i6 = BlockPos.getX(l4) - i;
                            int j5 = BlockPos.getY(l4) - j;
                            int k5 = BlockPos.getZ(l4) - k;
                            int l5 = i6 * i6 + j5 * j5 + k5 * k5;
                            if (k1 >= l5) {
                                l2 = k2;
                                k2 = j2;
                                j2 = l4;
                                i2 = l1;
                                l1 = k1;
                                k1 = l5;
                            } else if (l1 >= l5) {
                                l2 = k2;
                                k2 = l4;
                                i2 = l1;
                                l1 = l5;
                            } else if (i2 >= l5) {
                                l2 = l4;
                                i2 = l5;
                            }
                        }
                    }
                }

                Aquifer.FluidStatus aquifer$fluidstatus1 = this.getAquiferStatus(j2);
                double d1 = similarity(k1, l1);
                BlockState blockstate = aquifer$fluidstatus1.at(j);
                if (d1 <= 0.0) {
                    this.shouldScheduleFluidUpdate = d1 >= FLOWING_UPDATE_SIMULARITY;
                    return blockstate;
                } else if (blockstate.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(i, j - 1, k).at(j - 1).is(Blocks.LAVA)) {
                    this.shouldScheduleFluidUpdate = true;
                    return blockstate;
                } else {
                    MutableDouble mutabledouble;
                    if (SLEEP$SCRATCH) {
                        mutabledouble = sleep$substance;
                        mutabledouble.setValue(Double.NaN);
                    } else mutabledouble = new MutableDouble(Double.NaN);
                    Aquifer.FluidStatus aquifer$fluidstatus2 = this.getAquiferStatus(k2);
                    double d2 = d1 * this.calculatePressure(context, mutabledouble, aquifer$fluidstatus1, aquifer$fluidstatus2);
                    if (substance + d2 > 0.0) {
                        this.shouldScheduleFluidUpdate = false;
                        return null;
                    } else {
                        Aquifer.FluidStatus aquifer$fluidstatus3 = this.getAquiferStatus(l2);
                        double d0 = similarity(k1, i2);
                        if (d0 > 0.0) {
                            double d3 = d1 * d0 * this.calculatePressure(context, mutabledouble, aquifer$fluidstatus1, aquifer$fluidstatus3);
                            if (substance + d3 > 0.0) {
                                this.shouldScheduleFluidUpdate = false;
                                return null;
                            }
                        }

                        double d4 = similarity(l1, i2);
                        if (d4 > 0.0) {
                            double d5 = d1 * d4 * this.calculatePressure(context, mutabledouble, aquifer$fluidstatus2, aquifer$fluidstatus3);
                            if (substance + d5 > 0.0) {
                                this.shouldScheduleFluidUpdate = false;
                                return null;
                            }
                        }

                        this.shouldScheduleFluidUpdate = true;
                        return blockstate;
                    }
                }
            }
        }
    }

    /**
     * @author probe
     * @reason the fast path's flag, from the same two nearest distances vanilla would have used
     */
    @Overwrite
    public boolean shouldScheduleFluidUpdate() {
        if (sleep$flagPending) {
            sleep$flagPending = false;
            int i = sleep$fx, j = sleep$fy, k = sleep$fz;
            int l = Math.floorDiv(i - 5, 16);
            int i1 = Math.floorDiv(j + 1, 12);
            int j1 = Math.floorDiv(k - 5, 16);
            int k1 = Integer.MAX_VALUE;
            int l1 = Integer.MAX_VALUE;
            for (int i3 = 0; i3 <= 1; i3++)
                for (int j3 = -1; j3 <= 1; j3++)
                    for (int k3 = 0; k3 <= 1; k3++) {
                        long l4 = sleep$location(l + i3, i1 + j3, j1 + k3);
                        int i6 = BlockPos.getX(l4) - i;
                        int j5 = BlockPos.getY(l4) - j;
                        int k5 = BlockPos.getZ(l4) - k;
                        int l5 = i6 * i6 + j5 * j5 + k5 * k5;
                        if (k1 >= l5) {
                            l1 = k1;
                            k1 = l5;
                        } else if (l1 >= l5) {
                            l1 = l5;
                        }
                    }
            double d1 = similarity(k1, l1);
            this.shouldScheduleFluidUpdate = d1 <= 0.0 ? d1 >= FLOWING_UPDATE_SIMULARITY : true;
        }
        return this.shouldScheduleFluidUpdate;
    }
}
