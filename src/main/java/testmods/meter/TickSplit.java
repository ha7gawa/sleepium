package testmods.meter;

/**
 * Test only (-Dsleep.meter.tickSplit=true, with the frame log): Minecraft.tick split into its large calls, the time of
 * each summed per frame (ClientMeter's frame log adds a column per part). Changes nothing in the game. Outside the
 * mixin package (testmods.ticksplit): Mixin refuses to load other classes from it.
 */
public final class TickSplit {
    private TickSplit() {}

    public static final boolean ON = Boolean.getBoolean("sleep.meter.tickSplit");
    public static final String[] PARTS = {"entities", "level", "animate", "particles", "sound", "levelrenderer", "gamerenderer", "textures"};
    public static final long[] ACC = new long[PARTS.length];
    private static final long[] T0 = new long[PARTS.length];

    public static void begin(int part) {
        T0[part] = System.nanoTime();
    }

    public static void end(int part) {
        ACC[part] += System.nanoTime() - T0[part];
    }
}
