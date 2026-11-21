package dev.lull.opt;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * lab.opt.NoiseMathMixin (part): PerlinNoise.wrap returns small coordinates at once. For |value| < 2^23,
 * value / 2^25 + 0.5 lies in (0.25, 0.75), its floor is 0 and vanilla returns value - 0.0, which is value itself (also
 * for -0.0); larger values and NaN take vanilla's expression. Three wraps per octave, nearly all of them small.
 */
@Mixin(PerlinNoise.class)
public abstract class NoiseMathWrapMixin {
    /**
     * @author probe
     * @reason the exact early return for coordinates that never wrap
     */
    @Overwrite
    public static double wrap(double value) {
        return Math.abs(value) < 8388608.0 ? value : value - (double) Mth.lfloor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
    }
}
