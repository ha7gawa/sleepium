package dev.lull.optsupport;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * FrameThread: the client level's random source answered per thread - the frame thread (render code that draws from
 * level.random: some mods' entity renderers) gets a source of its own, the main thread keeps the level's. With the frame
 * drawn while the main thread ticks, one shared source would be used by two threads at once (C2ME's checked random
 * refuses it: "ThreadLocalRandom accessed from a different thread"); the tick's sequence no longer depends on drawing.
 */
public final class FrameRandom implements RandomSource {
    private final RandomSource main;
    private final RandomSource frame = RandomSource.create(0x5EE91A11L);

    private FrameRandom(RandomSource main) {
        this.main = main;
    }

    /**
     * the level's own source behind the wrapper (ParallelEntities compares draws with it: LegacyRandomSource.next sees the
     * wrapped source as this - with the wrapper compared, no draw ever matched and random-drawing classes were never
     * refused from the parallel tick; the frame thread audit, 11-16)
     */
    public static Object unwrap(Object r) {
        return r instanceof FrameRandom f ? f.main : r;
    }

    private RandomSource rs() {
        return FrameThread.onFrameThread() ? frame : main;
    }

    private static Object lastLevel;
    private static long offset = -1;

    /** the main thread: the level's random wrapped once per level (a final field: set through Unsafe) */
    static void install(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level == null || level == lastLevel) return;
        lastLevel = level;
        try {
            java.lang.reflect.Field uf = dev.lull.Names.fieldOf(sun.misc.Unsafe.class, "theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
            if (offset < 0) offset = u.objectFieldOffset(dev.lull.Names.fieldOf(net.minecraft.world.level.Level.class, "random"));
            Object cur = u.getObject(level, offset);
            if (!(cur instanceof FrameRandom)) u.putObject(level, offset, new FrameRandom((RandomSource) cur));
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("sleep").warn("[sleep/opt] frame thread: the level's random not wrapped ({})", t.toString());
        }
    }

    @Override public RandomSource fork() { return rs().fork(); }
    @Override public PositionalRandomFactory forkPositional() { return rs().forkPositional(); }
    @Override public void setSeed(long seed) { rs().setSeed(seed); }
    @Override public int nextInt() { return rs().nextInt(); }
    @Override public int nextInt(int bound) { return rs().nextInt(bound); }
    @Override public int nextIntBetweenInclusive(int min, int max) { return rs().nextIntBetweenInclusive(min, max); }
    @Override public long nextLong() { return rs().nextLong(); }
    @Override public boolean nextBoolean() { return rs().nextBoolean(); }
    @Override public float nextFloat() { return rs().nextFloat(); }
    @Override public double nextDouble() { return rs().nextDouble(); }
    @Override public double nextGaussian() { return rs().nextGaussian(); }
    @Override public double triangle(double center, double width) { return rs().triangle(center, width); }
    @Override public void consumeCount(int count) { rs().consumeCount(count); }
    @Override public int nextInt(int origin, int bound) { return rs().nextInt(origin, bound); }
}
