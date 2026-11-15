package dev.lull;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * -Dsleep.drawStats (client): GPU time of a frame from two timestamp queries (start and end of GameRenderer.render).
 * A ring of 64 frames; every 32 frames the half written 32..63 frames ago is read (long finished), so reading results
 * costs one driver round trip per 32 frames and does not change what is measured.
 */
public final class GpuTimer {
    private static final int RING = 64, HALF = RING / 2;
    private static final int[] BEGIN = new int[RING], END = new int[RING];
    private static int slot;
    private static long written, frames, gpuNanos, notReady;
    /** the GPU time of each frame since the reset (ms; NaN: not read) - the frame log's gpu_ms column */
    private static final float[] PER_FRAME = new float[1 << 16];
    private static final int[] SLOT_FRAME = new int[RING];

    public static float frameMs(int i) {
        return i >= 0 && i < PER_FRAME.length ? PER_FRAME[i] : Float.NaN;
    }

    /** each frame's end on the GPU clock (ns; 0: not read) - the frame log's gpu_gap_ms: the interval the GPU finished frames at */
    private static final long[] END_AT = new long[1 << 16];

    /**
     * the GPU clock when the CPU began a frame's rendering (glGetInteger64(GL_TIMESTAMP): the GPU's time now) and the
     * frame's latency - from there to its end on the GPU (ms): how far the CPU runs ahead (the queue the driver keeps)
     */
    private static final long[] CPU_AT = new long[RING];
    private static final boolean LAT_ON = Boolean.getBoolean("sleep.drawStats.lat");
    private static final float[] LAT = new float[1 << 16];

    public static float latMs(int i) {
        return i >= 0 && i < LAT.length ? LAT[i] : Float.NaN;
    }

    /** the GPU's interval between the end of frame i-1 and of frame i (ms), NaN when either was not read */
    public static float gapMs(int i) {
        if (i <= 0 || i >= END_AT.length || END_AT[i] == 0 || END_AT[i - 1] == 0) return Float.NaN;
        return (END_AT[i] - END_AT[i - 1]) / 1e6f;
    }
    /** per frame slot: timestamps at renderLevel's section changes (DrawStats.section) and the sections' names */
    private static final int MAXS = 40;
    private static final int[][] SQ = new int[RING][];
    private static final String[][] SN = new String[RING][MAXS];
    private static final int[] SC = new int[RING];
    private static final java.util.Map<String, long[]> GPU_SECTIONS = new java.util.LinkedHashMap<>();
    /**
     * -Dsleep.drawStats.pipeline=true: per section also the GPU's pipeline statistics (GL 4.6 / ARB_pipeline_statistics_query)
     * - vertex shader invocations, fragment shader invocations, primitives out of clipping: where a section's GPU time
     * goes (more vertices, or more fragments - overdraw), comparable across renderers (it works under Iris too)
     */
    private static final boolean PIPELINE = Boolean.getBoolean("sleep.drawStats.pipeline");
    private static final int[] STAT_TARGETS = {0x82F0, 0x82F4, 0x82F7};   // VERTEX_SHADER / FRAGMENT_SHADER_INVOCATIONS, CLIPPING_OUTPUT_PRIMITIVES
    private static final int[][] PQ = new int[RING][];
    private static boolean statOpen;
    private static final java.util.Map<String, long[]> STAT_SECTIONS = new java.util.LinkedHashMap<>();

    private static void statSwitch(int c) {
        if (!PIPELINE) return;
        if (statOpen) for (int t : STAT_TARGETS) org.lwjgl.opengl.GL15C.glEndQuery(t);
        statOpen = false;
        if (c < 0) return;
        if (PQ[slot] == null) {
            PQ[slot] = new int[MAXS * 3];
            GL15C.glGenQueries(PQ[slot]);
        }
        for (int k = 0; k < 3; k++) org.lwjgl.opengl.GL15C.glBeginQuery(STAT_TARGETS[k], PQ[slot][c * 3 + k]);
        statOpen = true;
    }

    /** a timestamp where renderLevel enters section {@code name} (null: renderLevel ended) */
    static void section(String name) {
        if (!DrawStats.active || BEGIN[0] == 0) return;
        int c = SC[slot];
        if (c >= MAXS) return;
        if (SQ[slot] == null) {
            SQ[slot] = new int[MAXS];
            GL15C.glGenQueries(SQ[slot]);
        }
        GL33C.glQueryCounter(SQ[slot][c], GL33C.GL_TIMESTAMP);
        SN[slot][c] = name == null ? "(after renderLevel)" : name;
        SC[slot] = c + 1;
        statSwitch(c);
    }

    private GpuTimer() {}

    static void reset() {
        frames = 0;
        gpuNanos = 0;
        GPU_SECTIONS.clear();
        STAT_SECTIONS.clear();
        java.util.Arrays.fill(SC, 0);
        java.util.Arrays.fill(PER_FRAME, Float.NaN);
        java.util.Arrays.fill(END_AT, 0L);
        java.util.Arrays.fill(LAT, Float.NaN);
        notReady = 0;
        written = 0;
        slot = 0;
    }

    public static void begin() {
        if (!DrawStats.active) return;
        if (BEGIN[0] == 0) {
            GL15C.glGenQueries(BEGIN);
            GL15C.glGenQueries(END);
        }
        GL33C.glQueryCounter(BEGIN[slot], GL33C.GL_TIMESTAMP);
        // (-Dsleep.drawStats.lat: the GPU's time now - on the N100 the query itself synchronised the CPU with the GPU, 11-15)
        if (LAT_ON) CPU_AT[slot] = org.lwjgl.opengl.GL32C.glGetInteger64(GL33C.GL_TIMESTAMP);
        SC[slot] = 0;
    }

    public static void end() {
        if (!DrawStats.active || BEGIN[0] == 0) return;
        statSwitch(-1);
        GL33C.glQueryCounter(END[slot], GL33C.GL_TIMESTAMP);
        SLOT_FRAME[slot] = (int) written;
        written++;
        slot = (slot + 1) % RING;
        if (written >= RING) {
            // one slot a frame: the one about to be overwritten, written RING frames ago - read only if the driver says
            // its result is there (11-14: reading a half of the ring at once every 32 frames made the Intel driver wait
            // for the GPU - a 40-50 ms frame every 200 ms on the N100)
            int i = slot;
            if (GL15C.glGetQueryObjecti(END[i], GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                notReady++;
                return;
            }
            {
                long b = GL33C.glGetQueryObjecti64(BEGIN[i], GL15C.GL_QUERY_RESULT), e = GL33C.glGetQueryObjecti64(END[i], GL15C.GL_QUERY_RESULT);
                gpuNanos += e - b;
                frames++;
                int fno = SLOT_FRAME[i];
                if (fno < PER_FRAME.length) {
                    PER_FRAME[fno] = (e - b) / 1e6f;
                    END_AT[fno] = e;
                    if (LAT_ON) LAT[fno] = (e - CPU_AT[i]) / 1e6f;
                }
                int n = SC[i];   // the sections: from each timestamp to the next (the last to the frame's end)
                long prev = b;
                String prevName = "(before renderLevel)";
                for (int k = 0; k < n; k++) {
                    long t = GL33C.glGetQueryObjecti64(SQ[i][k], GL15C.GL_QUERY_RESULT);
                    GPU_SECTIONS.computeIfAbsent(prevName, x -> new long[1])[0] += t - prev;
                    prev = t;
                    prevName = SN[i][k];
                }
                GPU_SECTIONS.computeIfAbsent(prevName, x -> new long[1])[0] += e - prev;
                if (PIPELINE && PQ[i] != null)
                    for (int k = 0; k < n; k++) {
                        long[] acc = STAT_SECTIONS.computeIfAbsent(SN[i][k], x -> new long[3]);
                        for (int q = 0; q < 3; q++) acc[q] += GL33C.glGetQueryObjecti64(PQ[i][k * 3 + q], GL15C.GL_QUERY_RESULT);
                    }
            }
        }
    }

    static String report() {
        if (frames == 0) return "gpu time: no samples";
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "gpu time (render start -> end on the GPU clock): %.3f ms per frame (%d frames, %d not ready when read)",
                gpuNanos / 1e6 / frames, frames, notReady));
        sb.append("\n  gpu time by renderLevel section (ms per frame):");
        GPU_SECTIONS.forEach((k, t) -> sb.append(String.format(java.util.Locale.ROOT, " %s %.3f;", k, t[0] / 1e6 / frames)));
        if (PIPELINE) {
            sb.append("\n  pipeline statistics by section (thousands per frame: vertex invocations / fragment invocations / primitives):");
            STAT_SECTIONS.forEach((k, t) -> {
                if (t[0] + t[1] > 0) sb.append(String.format(java.util.Locale.ROOT, " %s %.0f / %.0f / %.0f;", k, t[0] / 1e3 / frames, t[1] / 1e3 / frames, t[2] / 1e3 / frames));
            });
        }
        return sb.toString();
    }
}
