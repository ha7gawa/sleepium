package dev.lull.opt;

import dev.lull.optsupport.NoiseMath;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * lab.opt.NoiseMathMixin: one Perlin octave with its gradients read from a flat double table (NoiseMath.GRAD) instead
 * of SimplexNoise.GRADIENT's int arrays. The same permutation lookups (k + gridZ + 1 as (k + gridZ) + 1: the same int),
 * the same products and sums in the same order: the same doubles. Kept under HotSpot's inlining limit for hot callees
 * (325 bytes of bytecode; written out in full it was 574 and stayed a call). Octave loop on HotSpot 21 together with
 * NoiseMathWrapMixin: 26.0 -> 14.8 ns per octave (out of the line: 18.9).
 */
@Mixin(ImprovedNoise.class)
public abstract class NoiseMathMixin {
    @Shadow @Final private byte[] p;

    /**
     * @author probe
     * @reason gradients from a flat double table; vanilla's expression and order kept
     */
    @Overwrite
    private double sampleAndLerp(int gridX, int gridY, int gridZ, double deltaX, double weirdDeltaY, double deltaZ, double deltaY) {
        byte[] p = this.p;
        int i = p[gridX & 0xFF] & 0xFF;
        int j = p[gridX + 1 & 0xFF] & 0xFF;
        int k = (p[i + gridY & 0xFF] & 0xFF) + gridZ;
        int l = (p[i + gridY + 1 & 0xFF] & 0xFF) + gridZ;
        int i1 = (p[j + gridY & 0xFF] & 0xFF) + gridZ;
        int j1 = (p[j + gridY + 1 & 0xFF] & 0xFF) + gridZ;
        double x1 = deltaX - 1.0;
        double y1 = weirdDeltaY - 1.0;
        double z1 = deltaZ - 1.0;
        return Mth.lerp3(Mth.smoothstep(deltaX), Mth.smoothstep(deltaY), Mth.smoothstep(deltaZ),
                NoiseMath.dot(p[k & 0xFF], deltaX, weirdDeltaY, deltaZ), NoiseMath.dot(p[i1 & 0xFF], x1, weirdDeltaY, deltaZ),
                NoiseMath.dot(p[l & 0xFF], deltaX, y1, deltaZ), NoiseMath.dot(p[j1 & 0xFF], x1, y1, deltaZ),
                NoiseMath.dot(p[k + 1 & 0xFF], deltaX, weirdDeltaY, z1), NoiseMath.dot(p[i1 + 1 & 0xFF], x1, weirdDeltaY, z1),
                NoiseMath.dot(p[l + 1 & 0xFF], deltaX, y1, z1), NoiseMath.dot(p[j1 + 1 & 0xFF], x1, y1, z1));
    }
}
