package dev.lull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * -Dsleep.drawStats=true (client, measured window only): immediate-mode draws per frame by render type (draw calls,
 * vertices, vertex bytes re-uploaded) and render-thread time per object by entity / block entity class. The answer to
 * "why does looking at a few hundred objects cost more than the whole terrain".
 */
public final class DrawStats {
    public static final boolean ON = Boolean.getBoolean("sleep.drawStats");
    static boolean active;
    static long frames;
    private static final Map<String, long[]> TYPES = new HashMap<>();     // draws, vertices, bytes
    private static final Map<Class<?>, long[]> OBJECTS = new HashMap<>(); // calls, nanos
    private static long depth, start;
    private static final long[] PHASE = new long[10], PHASE_START = new long[10];
    private static final String[] PHASE_NAMES = {"buffer swap (flipFrame)", "world (renderLevel)", "frame render (world + GUI)",
            "chunk multi-draw GL calls", "chunk layers (ChunkMultiDraw.draw)", "entity dispatcher render", "entity light",
            "entity shadow", "entity renderer call (render cache)", "  of it RenderCache.render"};

    public static void phaseBegin(int i) {
        if (active) PHASE_START[i] = System.nanoTime();
    }

    public static void phaseEnd(int i) {
        if (active && PHASE_START[i] != 0) PHASE[i] += System.nanoTime() - PHASE_START[i];
    }
    private static Class<?> current;
    /** renderLevel's profiler sections (DrawStatsPhasesMixin): nanos per section name, in first-seen order */
    private static final Map<String, long[]> SECTIONS = new java.util.LinkedHashMap<>();
    private static String section;
    private static long sectionAt;

    /** renderLevel entered a profiler section (null: renderLevel ended) */
    public static void section(String name) {
        if (!active) return;
        GpuTimer.section(name);
        long now = System.nanoTime();
        if (section != null) {
            long[] t = SECTIONS.computeIfAbsent(section, k -> new long[3]);
            t[0] += now - sectionAt;
            t[1] += now - sectionAt;
        }
        section = name;
        sectionAt = now;
        if (name == null) {   // renderLevel ended: a slow one adds its sections to the slow tally
            long sum = 0;
            for (long[] t : SECTIONS.values()) sum += t[1];
            boolean slow = sum > SLOW_NS;
            if (slow) slowFrames++;
            for (long[] t : SECTIONS.values()) {
                if (slow) t[2] += t[1];
                t[1] = 0;
            }
        }
    }
    private static final long SLOW_NS = (long) (Double.parseDouble(System.getProperty("sleep.drawStats.slowMs", "3")) * 1e6);
    private static int slowFrames;

    private DrawStats() {}

    static void start() {
        dev.lull.optsupport.ChunkMultiDraw.flushNanos = dev.lull.optsupport.ChunkMultiDraw.offNanos = dev.lull.optsupport.ChunkMultiDraw.cmdUpNanos = 0;
        dev.lull.optsupport.ChunkMultiDraw.lvNanos = dev.lull.optsupport.ChunkMultiDraw.loopNanos = dev.lull.optsupport.ChunkMultiDraw.drawNanos = 0;
        TYPES.clear();
        OBJECTS.clear();
        SECTIONS.clear();
        slowFrames = 0;
        section = null;
        java.util.Arrays.fill(PHASE, 0);
        java.util.Arrays.fill(PHASE_START, 0);
        GpuTimer.reset();
        frames = 0;
        active = true;
    }

    public static void frame() {
        if (active) frames++;
    }

    public static void draw(String type, int vertices, int bytes) {
        if (!active) return;
        long[] t = TYPES.computeIfAbsent(type, k -> new long[3]);
        t[0]++;
        t[1] += vertices;
        t[2] += bytes;
    }

    /** Outermost object render only (a passenger or a nested renderer counts in its outer object). */
    public static void begin(Class<?> c) {
        if (!active) return;
        if (depth++ == 0) {
            current = c;
            start = System.nanoTime();
        }
    }

    public static void end() {
        if (!active || depth == 0) return;
        if (--depth == 0) {
            long[] o = OBJECTS.computeIfAbsent(current, k -> new long[2]);
            o[0]++;
            o[1] += System.nanoTime() - start;
        }
    }

    static String report() {
        active = false;
        if (frames == 0) return "draw stats: no frames";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "draw stats over %d frames (per frame):", frames));
        long d = 0, v = 0, b = 0;
        for (long[] t : TYPES.values()) {
            d += t[0];
            v += t[1];
            b += t[2];
        }
        sb.append("\n  ").append(GpuTimer.report());
        for (int i = 0; i < PHASE.length; i++) sb.append(String.format(Locale.ROOT, "\n  %s: %.3f ms per frame", PHASE_NAMES[i], PHASE[i] / 1e6 / frames));
        sb.append(String.format(Locale.ROOT, "\n  chunk multi-draw flush %.3f ms per frame: offsets upload %.3f, commands upload %.3f, light volumes %.3f, draw loop %.3f (multi-draw calls %.3f)",
                dev.lull.optsupport.ChunkMultiDraw.flushNanos / 1e6 / frames, dev.lull.optsupport.ChunkMultiDraw.offNanos / 1e6 / frames,
                dev.lull.optsupport.ChunkMultiDraw.cmdUpNanos / 1e6 / frames, dev.lull.optsupport.ChunkMultiDraw.lvNanos / 1e6 / frames,
                dev.lull.optsupport.ChunkMultiDraw.loopNanos / 1e6 / frames, dev.lull.optsupport.ChunkMultiDraw.drawNanos / 1e6 / frames));
        sb.append("\n  renderLevel sections (ms per frame):");
        SECTIONS.forEach((k, t) -> sb.append(String.format(Locale.ROOT, " %s %.3f;", k, t[0] / 1e6 / frames)));
        sb.append(String.format(Locale.ROOT, "\n  slow renderLevels (over %.1f ms): %d, ms each by section:", SLOW_NS / 1e6, slowFrames));
        SECTIONS.forEach((k, t) -> { if (t[2] / 1e6 / Math.max(1, slowFrames) >= 0.05) sb.append(String.format(Locale.ROOT, " %s %.3f;", k, t[2] / 1e6 / Math.max(1, slowFrames))); });
        sb.append(String.format(Locale.ROOT, "\n  immediate draws %.1f, vertices %.0f, uploaded %.0f KiB", d / (double) frames, v / (double) frames, b / 1024.0 / frames));
        List<Map.Entry<String, long[]>> ts = new ArrayList<>(TYPES.entrySet());
        ts.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        for (int i = 0; i < Math.min(15, ts.size()); i++) {
            long[] t = ts.get(i).getValue();
            String name = ts.get(i).getKey();
            if (name.length() > 70) name = name.substring(0, 70);
            sb.append(String.format(Locale.ROOT, "\n  %-70s draws %7.1f  vertices %8.0f  KiB %7.1f", name, t[0] / (double) frames, t[1] / (double) frames, t[2] / 1024.0 / frames));
        }
        List<Map.Entry<Class<?>, long[]>> os = new ArrayList<>(OBJECTS.entrySet());
        os.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        long total = 0;
        for (long[] o : OBJECTS.values()) total += o[1];
        sb.append(String.format(Locale.ROOT, "\n  object renders: %.3f ms per frame", total / 1e6 / frames));
        for (int i = 0; i < Math.min(15, os.size()); i++) {
            long[] o = os.get(i).getValue();
            sb.append(String.format(Locale.ROOT, "\n  %-40s per frame %6.1f objects, %.3f ms, %.2f us each", os.get(i).getKey().getSimpleName(),
                    o[0] / (double) frames, o[1] / 1e6 / frames, o[1] / 1e3 / o[0]));
        }
        return sb.toString();
    }
}
