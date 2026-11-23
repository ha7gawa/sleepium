package dev.lull.optsupport;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * lab.fast (not exact): a level's random source behind a lock - mobs' brains draw from the level's random (DoNothing,
 * ...), and vanilla's LegacyRandomSource throws when two threads draw at once (11-23, the zoo). One draw at a time; the
 * order of the draws between threads is the run's.
 */
public final class LockedRandom implements RandomSource {
    private final RandomSource r;

    public LockedRandom(RandomSource r) {
        this.r = r;
    }

    @Override public synchronized RandomSource fork() { return r.fork(); }
    @Override public synchronized PositionalRandomFactory forkPositional() { return r.forkPositional(); }
    @Override public synchronized void setSeed(long seed) { r.setSeed(seed); }
    @Override public synchronized int nextInt() { return r.nextInt(); }
    @Override public synchronized int nextInt(int bound) { return r.nextInt(bound); }
    @Override public synchronized long nextLong() { return r.nextLong(); }
    @Override public synchronized boolean nextBoolean() { return r.nextBoolean(); }
    @Override public synchronized float nextFloat() { return r.nextFloat(); }
    @Override public synchronized double nextDouble() { return r.nextDouble(); }
    @Override public synchronized double nextGaussian() { return r.nextGaussian(); }
}
