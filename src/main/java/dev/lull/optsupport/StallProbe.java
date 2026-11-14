package dev.lull.optsupport;

/**
 * -Dlab.stallProbe=true (research): where the render thread stands in a long frame. A daemon thread looks every 20 ms at
 * how long the current frame (Minecraft.runTick) has run; past -Dlab.stallProbe.ms (120) it takes the render thread's
 * stack, three times a frame at most, and counts the stacks by their top frames. The report lists the most common
 * places - what the N100's 360-460 ms frames every ~2 s (horde scene, outside tick, render and the task queue) wait on.
 */
public final class StallProbe {
    private StallProbe() {}

    public static final boolean ON = Boolean.getBoolean("lab.stallProbe");
    private static final long LIMIT = Long.getLong("lab.stallProbe.ms", 120) * 1_000_000L;
    private static volatile long frameStart;
    private static volatile long frameId;
    private static volatile Thread render;
    private static final java.util.Map<String, int[]> SEEN = new java.util.concurrent.ConcurrentHashMap<>();
    public static volatile long samples, longFrames;

    public static void frameStart() {
        if (render == null) {
            render = Thread.currentThread();
            Thread t = new Thread(StallProbe::run, "sleep-stall-probe");
            t.setDaemon(true);
            t.start();
        }
        frameId++;
        frameStart = System.nanoTime();
    }

    private static void run() {
        long lastFrame = -1;
        int taken = 0;
        while (true) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
            long id = frameId, start = frameStart;
            if (System.nanoTime() - start < LIMIT) continue;
            if (id != lastFrame) {
                lastFrame = id;
                taken = 0;
                longFrames++;
            }
            if (taken >= 3) continue;
            taken++;
            StackTraceElement[] st = render.getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                if (n++ >= 14) break;
                sb.append("\n      ").append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
            }
            SEEN.computeIfAbsent(sb.toString(), k -> new int[1])[0]++;
            samples++;
        }
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("stall probe: " + longFrames + " frames over " + LIMIT / 1_000_000 + " ms, " + samples + " stacks; the most common:");
        SEEN.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(4)
                .forEach(e -> sb.append("\n    ").append(e.getValue()[0]).append(" x").append(e.getKey()));
        return sb.toString();
    }
}
