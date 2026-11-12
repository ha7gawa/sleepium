package dev.lull.optsupport;

/**
 * A measuring window's samples (the measured choices: CacheTune, PartsTune, GpuAnim's blend): their mean without the
 * slowest tenth - one garbage collection or a chunk arriving in the window must not decide what is kept on this machine
 * for good (Calibration). A fixed array, reused (no garbage while measuring).
 */
final class Samples {
    private final double[] v;
    private int n;

    Samples(int capacity) {
        v = new double[capacity];
    }

    void clear() {
        n = 0;
    }

    int size() {
        return n;
    }

    void add(double x) {
        if (n < v.length) v[n++] = x;
    }

    /** the mean of the fastest 90 % (sorted in place; the window is over then) */
    double trimmed() {
        if (n == 0) return 0;
        java.util.Arrays.sort(v, 0, n);
        int keep = Math.max(1, (int) Math.ceil(n * 0.9));
        double s = 0;
        for (int i = 0; i < keep; i++) s += v[i];
        return s / keep;
    }
}
