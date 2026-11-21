package dev.lull.opt;

import dev.lull.optsupport.Dfc;
import dev.lull.optsupport.Reflect;
import java.lang.invoke.MethodHandles;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * lab.opt.DfcNoiseChunkMixin: the NoiseChunk's corner columns and cell caches by compiled code (see Dfc). Built at the
 * first fillSlice / selectCellYZ of a NoiseChunk of a whole chunk (column probes keep vanilla's), with an empty Blender.
 * The cell cache fill is redirected here; the corner columns are taken by WorldgenShareSlicesMixin's fillSlice through
 * sleep$dfcCorners.
 */
@Mixin(NoiseChunk.class)
public abstract class DfcNoiseChunkMixin implements Dfc.Host {
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final List<?> cellCaches;
    @Shadow @Final private Blender blender;
    @Shadow @Final int cellCountXZ;
    @Shadow @Final int cellCountY;
    @Shadow @Final int cellNoiseMinY;
    @Shadow @Final int cellWidth;
    @Shadow @Final int cellHeight;

    @Unique private boolean sleep$dfcBuilt;
    @Unique private Dfc.Code sleep$dfcCell;
    @Unique private Dfc.Code sleep$dfcCorner;
    @Unique private Object sleep$dfcCellRoot;

    @Override
    public MethodHandles.Lookup sleep$dfcLookup() {
        return MethodHandles.lookup();
    }

    @Unique
    private void sleep$dfcBuild() {
        sleep$dfcBuilt = true;
        if (blender != Blender.empty() || cellCountXZ < 2) return;
        MethodHandles.Lookup l = MethodHandles.lookup();
        sleep$dfcCorner = Dfc.corners(l, interpolators, cellHeight, cellNoiseMinY, cellCountY);
        if (!cellCaches.isEmpty()) {
            Object f = Reflect.get(cellCaches.get(0), "noiseFiller");
            sleep$dfcCellRoot = f;
            sleep$dfcCell = Dfc.cell(l, f, cellWidth, cellHeight);
        }
    }

    @Override
    public boolean sleep$dfcCorners(boolean slice0, int i) {
        if (!sleep$dfcBuilt) sleep$dfcBuild();
        Dfc.Code c = sleep$dfcCorner;
        if (c == null) return false;
        c.corners((NoiseChunk) (Object) this, slice0, i);
        return true;
    }

    @Redirect(method = "selectCellYZ", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"))
    private void sleep$dfcCellFill(DensityFunction f, double[] values, DensityFunction.ContextProvider cp) {
        if (!sleep$dfcBuilt) sleep$dfcBuild();
        Dfc.Code c = sleep$dfcCell;
        if (c != null && f == sleep$dfcCellRoot) {
            c.cell((NoiseChunk) (Object) this, values);
            if (Dfc.VERIFY) {
                double[] t = new double[values.length];
                f.fillArray(t, cp);
                Dfc.check(values, t);
            }
            return;
        }
        f.fillArray(values, cp);
    }
}
