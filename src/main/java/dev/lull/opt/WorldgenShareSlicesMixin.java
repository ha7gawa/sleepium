package dev.lull.opt;

import dev.lull.optsupport.CellProof;
import dev.lull.optsupport.SliceColumns;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldgenShare (corners): vanilla's fillSlice, taking a border corner column from the neighbour that already computed
 * it and publishing its own (see SliceColumns). The counters advance exactly as in vanilla, so CacheOnce and the cell
 * caches behave the same for every column that is computed here.
 */
@Mixin(NoiseChunk.class)
public abstract class WorldgenShareSlicesMixin {
    @Shadow private int cellStartBlockX;
    @Shadow int inCellX;
    @Shadow @Final int cellCountXZ;
    @Shadow @Final int cellCountY;
    @Shadow @Final int cellNoiseMinY;
    @Shadow @Final private int firstCellX;
    @Shadow @Final private int firstCellZ;
    @Shadow private int cellStartBlockZ;
    @Shadow int inCellZ;
    @Shadow long arrayInterpolationCounter;
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final private DensityFunction.ContextProvider sliceFillingContextProvider;
    @Shadow @Final int cellWidth;
    @Shadow @Final private Blender blender;

    @Unique private RandomState sleep$random;
    @Unique private Object sleep$beardifier;
    /** 0 undecided, 1 share, 2 do not */
    @Unique private byte sleep$share;
    @Unique private SliceColumns sleep$table;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$keep(int cellCountXZ, RandomState random, int firstNoiseX, int firstNoiseZ, NoiseSettings noiseSettings,
                            DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings noiseGeneratorSettings,
                            Aquifer.FluidPicker fluidPicker, Blender blendifier, CallbackInfo ci) {
        sleep$random = random;
        sleep$beardifier = beardifier;
    }

    @Unique
    private boolean sleep$decide() {
        if (sleep$share == 0) {
            boolean ok = blender == Blender.empty() && sleep$random != null && !interpolators.isEmpty();
            if (ok) {
                List<DensityFunction> roots = new ArrayList<>(interpolators);
                ok = SliceColumns.WALK_EACH ? SliceColumns.cache2dAreFlat(roots)
                        : SliceColumns.cache2dAreFlat(sleep$random, sleep$beardifier, roots);
            }
            if (ok) sleep$table = SliceColumns.forState(sleep$random, cellNoiseMinY, cellCountY, cellWidth, interpolators.size());
            sleep$share = (byte) (ok ? 1 : 2);
        }
        return sleep$share == 1;
    }

    /**
     * @author probe
     * @reason border corner columns shared with the neighbouring chunk (see class doc); otherwise vanilla
     */
    @Overwrite
    private void fillSlice(boolean isSlice0, int start) {
        this.cellStartBlockX = start * this.cellWidth;
        this.inCellX = 0;
        boolean share = sleep$decide();
        boolean borderX = start == this.firstCellX || start == this.firstCellX + this.cellCountXZ;

        for (int i = 0; i < this.cellCountXZ + 1; i++) {
            int j = this.firstCellZ + i;
            this.cellStartBlockZ = j * this.cellWidth;
            this.inCellZ = 0;
            this.arrayInterpolationCounter++;
            boolean border = share && (borderX || i == 0 || i == this.cellCountXZ);
            if (border) {
                double[][] got = sleep$table.get(start, j);
                if (got != null) {
                    for (int n = 0; n < this.interpolators.size(); n++) {
                        CellProof.Corners c = (CellProof.Corners) this.interpolators.get(n);
                        double[] dst = (isSlice0 ? c.sleep$slice0() : c.sleep$slice1())[i];
                        System.arraycopy(got[n], 0, dst, 0, dst.length);
                    }
                    continue;
                }
            }

            for (NoiseChunk.NoiseInterpolator noisechunk$noiseinterpolator : this.interpolators) {
                CellProof.Corners c = (CellProof.Corners) noisechunk$noiseinterpolator;
                double[] adouble = (isSlice0 ? c.sleep$slice0() : c.sleep$slice1())[i];
                noisechunk$noiseinterpolator.fillArray(adouble, this.sliceFillingContextProvider);
            }
            if (border) {
                double[][] cols = new double[this.interpolators.size()][];
                for (int n = 0; n < cols.length; n++) {
                    CellProof.Corners c = (CellProof.Corners) this.interpolators.get(n);
                    cols[n] = (isSlice0 ? c.sleep$slice0() : c.sleep$slice1())[i].clone();
                }
                sleep$table.put(start, j, cols);
            }
        }

        this.arrayInterpolationCounter++;
    }
}
