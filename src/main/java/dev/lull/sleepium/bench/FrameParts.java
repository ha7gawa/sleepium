package dev.lull.sleepium.bench;

/** Bench measuring: CPU nanos per frame part (0 renderEntities, 1 setupTerrain, 2 renderBlockLayer), main thread. */
public final class FrameParts {
    private FrameParts() {}

    public static final boolean ON = Boolean.getBoolean("sleep.bench.parts");
    public static boolean counting;
    private static final long[] T0 = new long[3], SUM = new long[3];
    private static final String[] NAMES = {"renderEntities", "setupTerrain", "renderBlockLayer"};

    public static void start(int i) {
        if (ON && counting) T0[i] = System.nanoTime();
    }

    public static void end(int i) {
        if (ON && counting && T0[i] != 0) SUM[i] += System.nanoTime() - T0[i];
    }

    private static final java.util.Map<String, long[]> BY = new java.util.HashMap<>();
    private static final long[] STACK = new long[64];
    private static int depth;

    public static void enter() {
        if (ON && counting && depth < STACK.length) STACK[depth++] = System.nanoTime();
    }

    public static void exit(String key) {
        if (ON && counting && depth > 0) {
            long d = System.nanoTime() - STACK[--depth];
            long[] a = BY.computeIfAbsent(key, k -> new long[2]);
            a[0]++;
            a[1] += d;
        }
    }

    public static String report(int frames) {
        if (!ON || frames == 0) return "";
        StringBuilder b = new StringBuilder("frame parts (CPU ms per frame):");
        for (int i = 0; i < 3; i++) b.append(String.format(java.util.Locale.ROOT, " %s %.3f", NAMES[i], SUM[i] / 1e6 / frames));
        BY.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(12)
                .forEach(e -> b.append(String.format(java.util.Locale.ROOT, "%n[sleepbench]    %-28s %6.3f ms per frame, %5.0f a frame", e.getKey(), e.getValue()[1] / 1e6 / frames, (double) e.getValue()[0] / frames)));
        return b.toString();
    }
}
