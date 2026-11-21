package dev.lull.optsupport;

/**
 * NoiseLazyInterp (-Dlab.opt.NoiseLazyInterpMixin, 11-21, lab): a noise interpolator's per-block value made when it is
 * read, not for every interpolator at every block.
 *
 * <p>Vanilla's doFill steps every interpolator of the chunk through its trilinear chain for every block: at each y
 * updateForY (4 lerps by y of the cell's corners), at each x updateForX (2 lerps by x), at each z updateForZ (1 lerp by
 * z) - three ArrayList.forEach passes with a lambda per block (11 % of all worker samples in the End, 11-21). The value
 * is only ever read by the interpolator's compute (interpolating, not filling a cell); a block reads a few of them (the
 * final density's, the vein toggle's; others only when the vein needs them).
 *
 * <p>Here NoiseChunk.updateForY / X / Z keep the step's fraction and bump a counter per level (vanilla's field writes
 * unchanged); compute, where vanilla returns {@code this.value}, brings the chain up to date first: the z lerp when the
 * z counter moved, before it the x lerps when the x counter moved, before them the y lerps when the y counter moved -
 * the same Mth.lerp calls on the same corners and fractions, so the same doubles. Vanilla always steps y, then x, then z
 * before any read (doFill, iterateNoiseColumn), so a moved y counter comes with moved x and z counters. A new cell
 * (selectCellYZ, the corners change) drops the interpolator's counters, so its next read redoes the whole chain.
 */
public final class LazyInterp {
    private LazyInterp() {}

    /** -Dlab.interp.verify: every lazy read compared bit for bit with vanilla's chain made from scratch */
    public static final boolean VERIFY = Boolean.getBoolean("lab.interp.verify");
    private static final java.util.concurrent.atomic.AtomicLong READS = new java.util.concurrent.atomic.AtomicLong(), BAD = new java.util.concurrent.atomic.AtomicLong();

    public static void check(boolean same) {
        if (!same) BAD.incrementAndGet();
        long n = READS.incrementAndGet();
        if ((n & 0x3FFFFF) == 0) System.out.println("[sleep/opt] NoiseLazyInterp verify: " + n + " reads, " + BAD.get() + " differ");
    }

    /** NoiseChunk: the current step fractions and their counters */
    public interface Host {
        double sleep$fy();

        double sleep$fx();

        double sleep$fz();

        long sleep$sy();

        long sleep$sx();

        long sleep$sz();
    }
}
