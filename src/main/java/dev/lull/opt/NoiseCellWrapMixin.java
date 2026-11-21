package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.CellWrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * lab.opt.NoiseCellWrapMixin: the cell cache's function built without mapping the mapped final density again (see
 * CellWrap for why that mapping gives back what it started from). What remains of vanilla's pass 2 is done as vanilla
 * does it, in its order: the beardifier marker wrapped, add(finalDensity, beardifier) wrapped, the cell cache marker
 * wrapped into the CacheAllInCell. Taken only when the expression is exactly vanilla's
 * cacheAllInCell(add(finalDensity, beardifier marker)) over this chunk's mapped router, with an empty Blender; once per
 * NoiseChunk (WrapOperation's object is not a hot-path cost here).
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseCellWrapMixin {
    @Shadow @Final private Blender blender;
    @Shadow @Final private DensityFunctions.BeardifierOrMarker beardifier;

    @Unique private NoiseRouter sleep$routerIn;
    @Unique private NoiseRouter sleep$routerMapped;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter sleep$keepRouter(NoiseRouter r) {
        sleep$routerIn = r;
        return r;
    }

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter sleep$keepMapped(NoiseRouter r) {
        sleep$routerMapped = r;
        return r;
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction sleep$cellFunction(DensityFunction cell, DensityFunction.Visitor visitor, Operation<DensityFunction> op) {
        NoiseRouter in = sleep$routerIn, mapped = sleep$routerMapped;
        sleep$routerIn = null;
        sleep$routerMapped = null;
        DensityFunction marker = CellWrap.BEARDIFIER_MARKER;
        if (in != null && mapped != null && marker != null && blender == Blender.empty()) {
            DensityFunction f = mapped.finalDensity();
            if (cell.equals(DensityFunctions.cacheAllInCell(DensityFunctions.add(f, marker))) && CellWrap.selfMapping(in, beardifier, f)) {
                CellWrap.used++;
                DensityFunction b = visitor.apply(marker);
                return visitor.apply(DensityFunctions.cacheAllInCell(visitor.apply(DensityFunctions.add(f, b))));
            }
        }
        CellWrap.vanilla++;
        return op.call(cell, visitor);
    }
}
