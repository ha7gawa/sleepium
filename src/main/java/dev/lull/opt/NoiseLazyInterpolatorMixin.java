package dev.lull.opt;

import dev.lull.optsupport.LazyInterp;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NoiseLazyInterp (lab, with NoiseLazyInterpMixin): the interpolator's value brought up to date when compute reads it
 * (see LazyInterp); a new cell drops its counters.
 */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseLazyInterpolatorMixin {
    @Shadow @Final NoiseChunk this$0;
    @Shadow double[][] slice0;
    @Shadow double[][] slice1;
    @Shadow private double noise000;
    @Shadow private double noise001;
    @Shadow private double noise100;
    @Shadow private double noise101;
    @Shadow private double noise010;
    @Shadow private double noise011;
    @Shadow private double noise110;
    @Shadow private double noise111;
    @Shadow private double valueXZ00;
    @Shadow private double valueXZ10;
    @Shadow private double valueXZ01;
    @Shadow private double valueXZ11;
    @Shadow private double valueZ0;
    @Shadow private double valueZ1;
    @Shadow private double value;

    @Unique private long sleep$sy = -1, sleep$sx = -1, sleep$sz = -1;

    /**
     * @author probe
     * @reason NoiseLazyInterp: vanilla's corners, and the lazy chain's counters dropped (new corners)
     */
    @Overwrite
    void selectCellYZ(int y, int z) {
        this.noise000 = this.slice0[z][y];
        this.noise001 = this.slice0[z + 1][y];
        this.noise100 = this.slice1[z][y];
        this.noise101 = this.slice1[z + 1][y];
        this.noise010 = this.slice0[z][y + 1];
        this.noise011 = this.slice0[z + 1][y + 1];
        this.noise110 = this.slice1[z][y + 1];
        this.noise111 = this.slice1[z + 1][y + 1];
        this.sleep$sy = -1;
        this.sleep$sx = -1;
        this.sleep$sz = -1;
    }

    @Redirect(method = "compute", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;value:D",
            opcode = Opcodes.GETFIELD))
    private double sleep$value(NoiseChunk.NoiseInterpolator self) {
        LazyInterp.Host h = (LazyInterp.Host) this.this$0;
        long sz = h.sleep$sz();
        if (this.sleep$sz != sz) {
            long sx = h.sleep$sx();
            if (this.sleep$sx != sx) {
                long sy = h.sleep$sy();
                if (this.sleep$sy != sy) {
                    double y = h.sleep$fy();
                    this.valueXZ00 = Mth.lerp(y, this.noise000, this.noise010);
                    this.valueXZ10 = Mth.lerp(y, this.noise100, this.noise110);
                    this.valueXZ01 = Mth.lerp(y, this.noise001, this.noise011);
                    this.valueXZ11 = Mth.lerp(y, this.noise101, this.noise111);
                    this.sleep$sy = sy;
                }
                double x = h.sleep$fx();
                this.valueZ0 = Mth.lerp(x, this.valueXZ00, this.valueXZ10);
                this.valueZ1 = Mth.lerp(x, this.valueXZ01, this.valueXZ11);
                this.sleep$sx = sx;
            }
            this.value = Mth.lerp(h.sleep$fz(), this.valueZ0, this.valueZ1);
            this.sleep$sz = sz;
        }
        if (LazyInterp.VERIFY) {
            // vanilla's chain from scratch for the current corners and fractions
            double y = h.sleep$fy(), x = h.sleep$fx();
            double a = Mth.lerp(x, Mth.lerp(y, this.noise000, this.noise010), Mth.lerp(y, this.noise100, this.noise110));
            double b = Mth.lerp(x, Mth.lerp(y, this.noise001, this.noise011), Mth.lerp(y, this.noise101, this.noise111));
            LazyInterp.check(Double.doubleToRawLongBits(Mth.lerp(h.sleep$fz(), a, b)) == Double.doubleToRawLongBits(this.value));
        }
        return this.value;
    }
}
