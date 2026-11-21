package dev.lull.optsupport;

/**
 * lab.opt.NoiseSlicePoolMixin: a NoiseInterpolator's two corner slices ({@code double[cellCountXZ + 1][cellCountY + 1]},
 * allocated twice over by vanilla's allocateSlice: the rows of new double[i][j] are replaced right away) come from this
 * thread's pool when its NoiseChunk starts interpolating and go back when it stops, instead of being allocated with
 * every NoiseChunk (chunks and the one-column probes of structure placement) and kept for its whole life.
 *
 * <p>Exact: the slices are read only between initializeForFirstCellX and stopInterpolation (selectCellYZ, the corner
 * check of WorldgenSkip), and every element is written before it is read - initializeForFirstCellX fills slice0 and
 * every advanceCellX fills slice1 completely (fillSlice runs every interpolator's fillArray over every z column, and
 * every fillArray writes the whole array: fillAllDirectly over its length, a transform over every index, CacheOnce
 * copying its whole last array, WorldgenShare's shared columns copied whole), swapSlices only exchanges them. So a
 * slice's earlier contents are never seen. A pool per thread (no shared writes); taken and given back on the thread
 * that interpolates (doFill and iterateNoiseColumn interpolate start to end on one thread); a NoiseChunk that holds
 * slices and a second one interpolating on the same thread take different arrays. The interpolator drops its references
 * when it gives them back, so a finished chunk keeps nothing and sees nothing of the next one.
 */
public final class SlicePool {
    /** implemented by NoiseSlicePoolMixin on NoiseChunk.NoiseInterpolator */
    public interface Slices {
        void sleep$take(SlicePool pool, int rows, int cols);

        void sleep$give(SlicePool pool);
    }

    private static final ThreadLocal<SlicePool> POOL = ThreadLocal.withInitial(SlicePool::new);
    private static final int BINS = 4, DEPTH = 64;

    public static SlicePool get() {
        return POOL.get();
    }

    private final int[] rows = new int[BINS], cols = new int[BINS], size = new int[BINS];
    private final double[][][][] stacks = new double[BINS][DEPTH][][];
    private int nextBin;

    private int bin(int r, int c) {
        for (int b = 0; b < BINS; b++) if (rows[b] == r && cols[b] == c) return b;
        return -1;
    }

    /** a slice of r rows of c doubles (contents unspecified) */
    public double[][] take(int r, int c) {
        int b = bin(r, c);
        if (b >= 0 && size[b] > 0) {
            double[][] s = stacks[b][--size[b]];
            stacks[b][size[b]] = null;
            return s;
        }
        return new double[r][c];
    }

    public void give(double[][] s) {
        if (s == null || s.length == 0) return;
        int r = s.length, c = s[0].length;
        int b = bin(r, c);
        if (b < 0) {   // a new shape: the least recently opened bin is emptied for it
            b = nextBin;
            nextBin = (nextBin + 1) % BINS;
            java.util.Arrays.fill(stacks[b], null);
            rows[b] = r;
            cols[b] = c;
            size[b] = 0;
        }
        if (size[b] < DEPTH) stacks[b][size[b]++] = s;
    }
}
