package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.lull.optsupport.CellProof;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * WorldgenSkip (cells): keeps the NoiseChunk's final density function (the one the aquifer filler evaluates per block:
 * cacheAllInCell(add(finalDensity, beardifier)) wrapped for this chunk) and builds its CellProof once. Only with an
 * empty Blender (NoiseChunk caches then are pure) and vanilla's filler order (the aquifer lambda first: its non-null
 * answer is the block's state).
 */
@Mixin(NoiseChunk.class)
public abstract class WorldgenSkipCellsMixin implements CellProof.Host {
    @Shadow @Final private NoiseChunk.BlockStateFiller blockStateRule;
    @Shadow @Final private Blender blender;

    @Shadow @Final java.util.List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final private int firstCellX;
    @Shadow @Final private int firstCellZ;
    @Shadow @Final int cellCountXZ;
    @Shadow @Final int cellCountY;
    @Shadow @Final int cellNoiseMinY;
    @Shadow @Final int cellWidth;
    @Shadow @Final int cellHeight;

    @Unique private DensityFunction sleep$final;
    @Unique private net.minecraft.world.level.levelgen.NoiseRouter sleep$router;
    /** 0: filler list not vanilla; 1: aquifer only; 2: aquifer + ore veins */
    @Unique private int sleep$fillers;
    @Unique private CellProof sleep$vein;
    @Unique private static int[] PROBE$VEIN_RANGES;
    @Unique private CellProof sleep$proofValue;
    @Unique private boolean sleep$built;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction sleep$capture(DensityFunction f) {
        sleep$final = f;
        return f;
    }

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private net.minecraft.world.level.levelgen.NoiseRouter sleep$captureRouter(net.minecraft.world.level.levelgen.NoiseRouter r) {
        sleep$router = r;
        return r;
    }

    /** OreVeinifier.VeinType's [minY, maxY] pairs (a vein is only possible inside one) */
    @Unique
    private static int[] sleep$veinRanges() {
        int[] r = PROBE$VEIN_RANGES;
        if (r == null) {
            try {
                Class<?> t = Class.forName(dev.lull.Names.cls("net.minecraft.world.level.levelgen.OreVeinifier$VeinType"));
                Object[] vs = t.getEnumConstants();
                java.lang.reflect.Field mn = dev.lull.Names.fieldOf(t, "minY"), mx = dev.lull.Names.fieldOf(t, "maxY");
                mn.setAccessible(true);
                mx.setAccessible(true);
                r = new int[vs.length * 2];
                for (int i = 0; i < vs.length; i++) {
                    r[2 * i] = mn.getInt(vs[i]);
                    r[2 * i + 1] = mx.getInt(vs[i]);
                }
            } catch (ReflectiveOperationException e) {
                r = new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE};   // unknown: veins possible everywhere
            }
            PROBE$VEIN_RANGES = r;
        }
        return r;
    }

    @Override
    public boolean sleep$solidIsDefault(CellProof.Cell c) {
        if (sleep$fillers == 1) return true;
        if (sleep$fillers != 2) return false;
        int[] r = sleep$veinRanges();
        boolean inRange = false;
        for (int i = 0; i < r.length; i += 2) inRange |= c.y1 >= r[i] && c.y0 <= r[i + 1];
        if (!inRange) return true;   // OreVeinifier: a block outside every vein's [minY, maxY] gets null
        if (sleep$vein == null) return false;
        // inside: null unless |veinToggle| + edge (<= 0) >= 0.4F
        sleep$vein.allNegative(c);
        return sleep$vein.lastMaxAbs() < 0.4 - 1e-9;
    }

    @Override
    public CellProof sleep$proof() {
        if (!sleep$built) {
            sleep$built = true;
            if (blender == Blender.empty() && sleep$final != null && blockStateRule instanceof MaterialRuleList m
                    && !m.materialRuleList().isEmpty() && m.materialRuleList().get(0).getClass().getNestHost() == NoiseChunk.class) {
                sleep$proofValue = CellProof.build(sleep$final);
                java.util.List<NoiseChunk.BlockStateFiller> l = m.materialRuleList();
                if (l.size() == 1) sleep$fillers = 1;
                else if (l.size() == 2 && l.get(1).getClass().getNestHost() == net.minecraft.world.level.levelgen.OreVeinifier.class && sleep$router != null) {
                    sleep$fillers = 2;
                    sleep$vein = CellProof.build(sleep$router.veinToggle());
                }
            }
            if (sleep$proofValue != null && Boolean.getBoolean("lab.genscout")) sleep$measureCeiling();
        }
        return sleep$proofValue;
    }

    /** measurement (lab.genscout): rows of corner columns provably negative above a ceiling, best interpolator */
    @Unique
    private void sleep$measureCeiling() {
        long best = -1, rows = 0;
        for (NoiseChunk.NoiseInterpolator it : interpolators) {
            DensityFunction inner;
            try {
                java.lang.reflect.Field f = dev.lull.Names.fieldOf(NoiseChunk.NoiseInterpolator.class, "noiseFiller");
                f.setAccessible(true);
                inner = (DensityFunction) f.get(it);
            } catch (ReflectiveOperationException e) {
                return;
            }
            dev.lull.optsupport.ColumnBound cb = dev.lull.optsupport.ColumnBound.build(inner);
            if (cb == null) continue;
            long neg = 0, all = 0;
            for (int cx = 0; cx <= cellCountXZ; cx++)
                for (int cz = 0; cz <= cellCountXZ; cz++) {
                    int x = (firstCellX + cx) * cellWidth, z = (firstCellZ + cz) * cellWidth;
                    int top = cellCountY;   // corner rows 0..cellCountY
                    all += top + 1;
                    int r = top;
                    while (r >= 0) {
                        int r0 = Math.max(0, r - 3);
                        int ya = (r0 + cellNoiseMinY) * cellHeight, yb = (r + cellNoiseMinY) * cellHeight;
                        if (cb.upper(x, z, ya, yb) >= -1e-9) break;
                        neg += r - r0 + 1;
                        r = r0 - 1;
                    }
                }
            rows = all;
            best = Math.max(best, neg);
        }
        dev.lull.optsupport.ColumnBound.COLUMNS.add((long) (cellCountXZ + 1) * (cellCountXZ + 1));
        dev.lull.optsupport.ColumnBound.ROWS.add(rows);
        dev.lull.optsupport.ColumnBound.ROWS_NEGATIVE.add(Math.max(0, best));
    }
}
