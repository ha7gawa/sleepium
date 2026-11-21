package dev.lull;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.TreeMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Measurement (-Dlab.gen.timeline=true, 11-21): while a genbench area generates, every second server tick the area's
 * chunks are counted by the last status each holder has finished (none = not reached any yet) and the worker threads by
 * state (running vs waiting). Answers where chunks pile up while workers idle - the limit of generation's parallelism
 * (11-21: only 5-6 of 15 Worker-Main threads busy at 140-160 chunks/s, the main thread idle).
 */
final class GenTimeline {
    static final boolean ON = Boolean.getBoolean("lab.gen.timeline");
    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
    private static final StringBuilder LINES = new StringBuilder();
    /** per status: the sum of counts over samples (mean = sum / samples) */
    private static final TreeMap<String, Long> SUM = new TreeMap<>();
    private static long samples, runSum, workerSum, lowSamples;
    private static int tick;
    private static final TreeMap<String, Integer> TOP = new TreeMap<>();
    private static java.lang.reflect.Method visible;
    private static long lastBusy, lastSampleNanos;

    private GenTimeline() {}

    static void sample(ServerLevel level, int x0, int z0, int side, double secs) {
        if ((++tick & 1) != 0) return;
        TreeMap<String, Integer> counts = new TreeMap<>();
        try {
            if (visible == null) {
                visible = ChunkMap.class.getDeclaredMethod("getVisibleChunkIfPresent", long.class);
                visible.setAccessible(true);
            }
            ChunkMap map = level.getChunkSource().chunkMap;
            for (int dx = 0; dx < side; dx++) {
                for (int dz = 0; dz < side; dz++) {
                    ChunkHolder h = (ChunkHolder) visible.invoke(map, ChunkPos.asLong(x0 + dx, z0 + dz));
                    ChunkStatus s = h == null ? null : h.getPersistedStatus();
                    counts.merge(s == null ? "-" : shortName(s), 1, Integer::sum);
                }
            }
        } catch (ReflectiveOperationException e) {
            counts.put("error " + e.getClass().getSimpleName(), 1);
        }
        int running = 0, workers = 0, io = 0;
        String main = "?";
        for (ThreadInfo ti : MX.getThreadInfo(MX.getAllThreadIds(), 1)) {
            if (ti == null) continue;
            String n = ti.getThreadName();
            boolean run = ti.getThreadState() == Thread.State.RUNNABLE;
            if (n.startsWith("Worker-Main")) {
                workers++;
                if (run) {
                    running++;
                    StackTraceElement[] st = ti.getStackTrace();
                    if (st.length > 0) TOP.merge(st[0].getClassName().substring(st[0].getClassName().lastIndexOf('.') + 1) + "." + st[0].getMethodName(), 1, Integer::sum);
                }
            } else if (n.startsWith("IO-Worker") && run) io++;
            else if (n.equals("Server thread")) {
                StackTraceElement[] st = ti.getStackTrace();
                main = run ? (st.length > 0 ? st[0].getMethodName() : "run") : "wait";
            }
        }
        samples++;
        runSum += running;
        workerSum += workers;
        if (running * 2 < workers) lowSamples++;
        counts.forEach((k, v) -> SUM.merge(k, (long) v, Long::sum));
        LINES.append(String.format(Locale.ROOT, "  %6.2f s  workers %2d/%2d  io %d  main %-14s ", secs, running, workers, io, main.length() > 14 ? main.substring(0, 14) : main));
        if (LightProbe.ON) {   // the light actor: its busy share since the last sample, its queue now
            long busy = LightProbe.BUSY.get(), now = System.nanoTime();
            var eng = level.getChunkSource().getLightEngine();
            if (lastSampleNanos != 0)
                LINES.append(String.format(Locale.ROOT, "light %3.0f%% q %4d  ", 100.0 * (busy - lastBusy) / Math.max(1, now - lastSampleNanos),
                        eng instanceof LightProbe.Engine e ? e.sleep$pendingTasks() : -1));
            lastBusy = busy;
            lastSampleNanos = now;
        }
        if (running <= 2 && !TOP.isEmpty()) {   // the few busy workers of a quiet sample: what they run (top frame)
            LINES.append("[");
            TOP.forEach((k, v) -> LINES.append(k).append(' '));
            LINES.append("] ");
        }
        TOP.clear();
        counts.forEach((k, v) -> LINES.append(k).append(' ').append(v).append("  "));
        LINES.append('\n');
    }

    private static String shortName(ChunkStatus s) {
        String n = s.toString();
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    static String reportAndReset() {
        StringBuilder sb = new StringBuilder("generation timeline (every 2nd tick: the area's chunks by the last finished status, Worker-Main running / all):\n");
        if (samples > 0) {
            sb.append(String.format(Locale.ROOT, "  mean workers running %.1f of %.1f; samples with under half running: %d of %d; mean chunks by status:",
                    (double) runSum / samples, (double) workerSum / samples, lowSamples, samples));
            SUM.forEach((k, v) -> sb.append(String.format(Locale.ROOT, " %s %.0f", k, (double) v / samples)));
            sb.append('\n');
        }
        sb.append(LINES);
        LINES.setLength(0);
        SUM.clear();
        samples = runSum = workerSum = lowSamples = 0;
        tick = 0;
        lastSampleNanos = 0;
        return sb.toString();
    }
}
