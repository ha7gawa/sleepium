package dev.lull.optsupport;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * lab.fast (not exact, notes/101 part 2): Sleepium's own random numbers - one 64-bit state stepped by a Weyl sequence and
 * mixed (SplitMix64 / wyrand family), no atomics, no lock, no thread check. Vanilla's LegacyRandomSource is a 48-bit LCG
 * behind a compareAndSet (and throws when two threads meet); a mob's draws here cost a few plain instructions.
 * Not thread-safe by design: a stream is one entity's, or one thread's (LevelRandom). Two threads that do meet on one
 * stream (a projectile's hit on a mob of another group) only get less independent numbers - no exception, no hang.
 */
public final class FastRandom implements RandomSource {
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final java.util.concurrent.atomic.AtomicLong SEEDS = new java.util.concurrent.atomic.AtomicLong(System.nanoTime() ^ 0x5DEECE66DL);

    private long state;
    private double nextGauss;
    private boolean haveGauss;

    public FastRandom(long seed) {
        state = mix(seed);
    }

    /** a fresh stream: seeds of distinct streams spread by the golden ratio, then mixed */
    public static FastRandom fresh() {
        return new FastRandom(SEEDS.addAndGet(GOLDEN));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private long next64() {
        return mix(state += GOLDEN);
    }

    @Override public RandomSource fork() { return new FastRandom(next64()); }

    @Override public PositionalRandomFactory forkPositional() { return new XoroshiroRandomSource(next64(), next64()).forkPositional(); }

    @Override public void setSeed(long seed) {
        state = mix(seed);
        haveGauss = false;
    }

    @Override public int nextInt() { return (int) (next64() >>> 32); }

    /** Lemire's multiply-shift: [0, bound) from the high 32 bits, no division (bias below 2^-32 * bound, not exact anyway) */
    @Override public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        return (int) (((next64() >>> 32) * bound) >>> 32);
    }

    @Override public long nextLong() { return next64(); }

    @Override public boolean nextBoolean() { return next64() < 0; }

    @Override public float nextFloat() { return (next64() >>> 40) * 0x1.0p-24f; }

    @Override public double nextDouble() { return (next64() >>> 11) * 0x1.0p-53; }

    /** Marsaglia's polar method (as vanilla's), the second value kept */
    @Override public double nextGaussian() {
        if (haveGauss) {
            haveGauss = false;
            return nextGauss;
        }
        double v1, v2, s;
        do {
            v1 = 2 * nextDouble() - 1;
            v2 = 2 * nextDouble() - 1;
            s = v1 * v1 + v2 * v2;
        } while (s >= 1 || s == 0);
        double m = Math.sqrt(-2 * Math.log(s) / s);
        nextGauss = v2 * m;
        haveGauss = true;
        return v1 * m;
    }

    /**
     * a server level's random in lab.fast: the server thread's own stream (one field compare), every other thread
     * (the run workers) its own stream - no lock, no shared state. setSeed (vanilla never seeds a level's random) only
     * reseeds the caller's stream.
     */
    public static final class LevelRandom implements RandomSource {
        private final Thread owner;
        private final FastRandom main;
        private final ThreadLocal<FastRandom> others = ThreadLocal.withInitial(FastRandom::fresh);

        public LevelRandom(Thread owner) {
            this.owner = owner;
            this.main = FastRandom.fresh();
        }

        private FastRandom r() {
            return Thread.currentThread() == owner ? main : others.get();
        }

        @Override public RandomSource fork() { return r().fork(); }
        @Override public PositionalRandomFactory forkPositional() { return r().forkPositional(); }
        @Override public void setSeed(long seed) { r().setSeed(seed); }
        @Override public int nextInt() { return r().nextInt(); }
        @Override public int nextInt(int bound) { return r().nextInt(bound); }
        @Override public long nextLong() { return r().nextLong(); }
        @Override public boolean nextBoolean() { return r().nextBoolean(); }
        @Override public float nextFloat() { return r().nextFloat(); }
        @Override public double nextDouble() { return r().nextDouble(); }
        @Override public double nextGaussian() { return r().nextGaussian(); }
    }
}
