package dev.lull.optsupport;

/**
 * -Dlab.frameThread.turnLog=true (research, the extra frames' camera turn checked by hand): every frame's camera as the
 * frame thread set it up - time, an extra frame or a recorded one, yaw, pitch, the turn added - kept in memory and
 * written to sleepium/turnlog.csv when the game ends (or the arrays fill: 400 000 frames).
 */
public final class TurnLog {
    private TurnLog() {}

    public static final boolean ON = Boolean.getBoolean("lab.frameThread.turnLog");
    private static final int CAP = 400_000;
    private static final long[] T = ON ? new long[CAP] : null;
    private static final float[] YAW = ON ? new float[CAP] : null, PITCH = ON ? new float[CAP] : null, ADD = ON ? new float[CAP] : null;
    private static final boolean[] EXTRA = ON ? new boolean[CAP] : null;
    private static int n;
    private static boolean hooked, written;

    /** the frame thread, Camera.setup's end */
    public static void add(boolean extra, float yaw, float pitch, float added) {
        if (!hooked) {
            hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(TurnLog::write, "sleep-turnlog"));
        }
        if (n >= CAP) return;
        T[n] = System.nanoTime();
        EXTRA[n] = extra;
        YAW[n] = yaw;
        PITCH[n] = pitch;
        ADD[n] = added;
        n++;
        if (n == CAP) write();
    }

    private static synchronized void write() {
        if (written || n == 0) return;
        written = true;
        java.nio.file.Path p = java.nio.file.Path.of("sleepium", "turnlog.csv");
        try {
            java.nio.file.Files.createDirectories(p.getParent());
            try (java.io.PrintWriter w = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(p))) {
                w.println("t_ms,extra,yaw,pitch,turn_added");
                long t0 = T[0];
                for (int i = 0; i < n; i++)
                    w.printf(java.util.Locale.ROOT, "%.3f,%d,%.3f,%.3f,%.3f%n", (T[i] - t0) / 1e6, EXTRA[i] ? 1 : 0, YAW[i], PITCH[i], ADD[i]);
            }
            System.out.println("[sleep/opt] turn log: " + n + " frames -> " + p.toAbsolutePath());
        } catch (java.io.IOException e) {
            System.out.println("[sleep/opt] turn log not written: " + e);
        }
    }
}
