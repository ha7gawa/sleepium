package dev.lull.optsupport;

/**
 * lab.opt.NoiseMathMixin: the tables of the Perlin octave rewrite (NoiseMathMixin, NoiseMathWrapMixin).
 *
 * <p>Vanilla takes a corner's gradient as an int[3] from SimplexNoise.GRADIENT (two dependent loads and three int to
 * double conversions per corner, eight corners per octave); here the same sixteen gradients are doubles in one flat
 * array, four slots per gradient. Every component is -1, 0 or 1, exact as a double, so g * x is the same product
 * vanilla computes from (double) g.
 */
public final class NoiseMath {
    private NoiseMath() {}

    /** SimplexNoise.GRADIENT as doubles: gradient h at [4h, 4h + 2], slot 4h + 3 unused */
    public static final double[] GRAD = new double[64];

    /**
     * The gradient dot product of vanilla's ImprovedNoise.gradDot for a permutation byte (only its low 4 bits pick
     * the gradient): (double) g[0] * x + (double) g[1] * y + (double) g[2] * z, the same operations in the same order.
     */
    public static double dot(byte perm, double x, double y, double z) {
        int h = (perm & 15) << 2;
        double[] g = GRAD;
        return g[h] * x + g[h + 1] * y + g[h + 2] * z;
    }

    static {
        int[][] g = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, 1},
                {0, 1, -1}, {0, -1, -1}, {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}};
        for (int i = 0; i < 16; i++) for (int j = 0; j < 3; j++) GRAD[i * 4 + j] = g[i][j];
    }
}
