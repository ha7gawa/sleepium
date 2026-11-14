package dev.lull.optsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * StallProbe's GL side (-Dlab.stallProbe): per frame, what the frame did to GL objects through vanilla's GlStateManager
 * (buffers and vertex arrays made / deleted, glBufferData calls and bytes, textures made / deleted / sized, shaders
 * compiled, programs linked, buffers mapped) and the driver's debug messages of every kind (with -Dsleep.glTrace). A frame
 * over the probe's limit is written down with the frame before it; the report adds the mean of all frames to compare.
 * The question (11-14): the N100's 370 ms waits in glfwSwapBuffers when a wave of mobs arrives - the GPU idle meanwhile.
 */
public final class GlFrame {
    private GlFrame() {}

    public static final int GEN_BUF = 0, DEL_BUF = 1, BUF_DATA = 2, BUF_BYTES = 3, GEN_VAO = 4, DEL_VAO = 5, GEN_TEX = 6, DEL_TEX = 7,
            TEX_IMAGE = 8, TEX_BYTES = 9, COMPILE = 10, LINK = 11, MAP = 12, MSG = 13, ENTITIES_ADDED = 14, N = 15;
    private static final String[] NAMES = {"gen buf", "del buf", "bufData", "bufData KiB", "gen vao", "del vao", "gen tex", "del tex",
            "texImage", "texImage KiB", "compile", "link", "map", "driver msgs", "entities added"};

    private static final long[] cur = new long[N], prev = new long[N], total = new long[N];
    private static final List<String> curMsgs = new ArrayList<>(), prevMsgs = new ArrayList<>();
    private static long frames;
    private static final List<String> LONG = new ArrayList<>();

    /** render thread (GlStateManager is render-thread only; the debug callback runs inside a GL call on it) */
    public static void count(int kind, long n) {
        cur[kind] += n;
    }

    public static void msg(int type, int severity, int id, String text) {
        cur[MSG]++;
        if (curMsgs.size() < 12) curMsgs.add(String.format(Locale.ROOT, "type 0x%x sev 0x%x id %d: %s", type, severity, id,
                text.length() > 160 ? text.substring(0, 160) : text));
    }

    /** the frame that just ended took {@code nanos}; called at the next frame's start */
    static void frameEnd(long nanos, long limitNanos) {
        frames++;
        for (int i = 0; i < N; i++) total[i] += cur[i];
        if (nanos > limitNanos) {
            if (LONG.size() >= 16) LONG.remove(0);   // the last 16: the measurement window is at the end of a run, loading at its start
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "\n    frame %d: %.0f ms - this frame: %s; the frame before: %s",
                    frames, nanos / 1e6, line(cur), line(prev)));
            for (String m : prevMsgs) sb.append("\n        before: ").append(m);
            for (String m : curMsgs) sb.append("\n        this:   ").append(m);
            LONG.add(sb.toString());
        }
        System.arraycopy(cur, 0, prev, 0, N);
        java.util.Arrays.fill(cur, 0);
        prevMsgs.clear();
        prevMsgs.addAll(curMsgs);
        curMsgs.clear();
    }

    private static String line(long[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++) {
            if (v[i] == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            long x = i == BUF_BYTES || i == TEX_BYTES ? v[i] >> 10 : v[i];
            sb.append(NAMES[i]).append(' ').append(x);
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    static String report() {
        StringBuilder sb = new StringBuilder("gl per frame (mean of " + frames + "):");
        for (int i = 0; i < N; i++) {
            double m = frames == 0 ? 0 : (double) total[i] / frames;
            if (i == BUF_BYTES || i == TEX_BYTES) m /= 1024;
            if (m > 0) sb.append(String.format(Locale.ROOT, " %s %.2f;", NAMES[i], m));
        }
        sb.append(" long frames:");
        for (String s : LONG) sb.append(s);
        return sb.toString();
    }
}
