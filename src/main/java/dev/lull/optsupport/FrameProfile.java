package dev.lull.optsupport;

import java.util.function.Supplier;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

/**
 * -Dlab.frameProfile=true (measuring): the frame thread's frame by vanilla's own profiler sections (GameRenderer /
 * LevelRenderer push and pop them: terrain_setup, compile_sections, terrain, entities, blockentities, particles,
 * weather, gui ...) - Minecraft.getProfiler answers this profiler on the frame thread (FrameProfileMixin), which sums
 * the time per section path in the measured window. One thread, no locks; a path's slot is found by the parent slot
 * and the name (a small open table, no allocation once the paths are known). The CPU time of each section, GL calls
 * included as far as the driver blocks in them - the frame thread's serial work by part: what a frame graph could split.
 */
public final class FrameProfile implements ProfilerFiller {
    public static final boolean ON = Boolean.getBoolean("lab.frameProfile");
    public static final FrameProfile INSTANCE = new FrameProfile("frame", "the frame thread", "frames");
    /**
     * -Dlab.serverProfile=true (measuring): the same for the server thread's tick (MinecraftServer.getProfiler on the
     * server thread, FrameProfileServerMixin) - its sections by vanilla's names (levels, entities, blockEntities,
     * chunkSource, connection ...), per tick
     */
    public static final boolean SERVER_ON = Boolean.getBoolean("lab.serverProfile");
    public static final FrameProfile SERVER = new FrameProfile("tick", "the server thread", "ticks");
    private final String what, unit;

    private static final int MAX = 512, DEPTH = 64;
    private final String[] names = new String[MAX];
    private final int[] parent = new int[MAX];
    private final long[] total = new long[MAX], count = new long[MAX];
    private int n = 1;   // (slot 0: the root)
    private final int[] stack = new int[DEPTH];
    private final long[] startAt = new long[DEPTH];
    private int depth;
    private long frames;
    /**
     * -Dlab.frameProfile.slowUs (4000): frames longer than this (start to next start) also summed apart - the slow
     * frames' sections next to the average ones (the user, 11-17: the dips are the 1 % low, not the average)
     */
    private static final long SLOW_NS = Long.getLong("lab.frameProfile.slowUs", 4000) * 1000L;
    private final long[] frameT = new long[MAX], slowT = new long[MAX];
    private long slowFrames, lastFrameAt;

    private FrameProfile(String root, String what, String unit) {
        names[0] = root;
        parent[0] = -1;
        this.what = what;
        this.unit = unit;
    }

    /** the server thread at a tick's start (MinecraftServer.tickServer) */
    public static void tick() {
        SERVER.depth = 0;
        if (measuringServer()) SERVER.frames++;
    }

    /** the server's window: the client's measured window (an integrated server), else always */
    private static boolean measuringServer() {
        return dev.lull.ClientMeter.measuring();
    }

    private boolean counting() {
        return this == SERVER ? measuringServer() : dev.lull.ClientMeter.measuring();
    }

    /** the window's frames counted (the frame thread's GameRenderer.render calls) */
    public static void frame() {
        FrameProfile f = INSTANCE;
        f.depth = 0;   // (a frame starts at the root: a section left open by another mod's early return ends here)
        long now = System.nanoTime();
        if (dev.lull.ClientMeter.measuring()) {
            f.frames++;
            if (f.lastFrameAt != 0 && now - f.lastFrameAt > SLOW_NS) {
                for (int i = 1; i < f.n; i++) f.slowT[i] += f.frameT[i];
                f.slowFrames++;
            }
        }
        java.util.Arrays.fill(f.frameT, 0, f.n, 0);
        f.lastFrameAt = now;
    }

    private int child(int p, String name) {
        for (int i = 1; i < n; i++) if (parent[i] == p && names[i].equals(name)) return i;
        if (n == MAX) return -1;
        names[n] = name;
        parent[n] = p;
        return n++;
    }

    @Override
    public void push(String name) {
        if (depth >= DEPTH) {
            depth++;
            return;
        }
        int p = depth == 0 ? 0 : stack[depth - 1];
        stack[depth] = p < 0 ? -1 : child(p, name);
        startAt[depth] = System.nanoTime();
        depth++;
    }

    @Override
    public void push(Supplier<String> name) {
        push(name.get());
    }

    @Override
    public void pop() {
        if (depth == 0) return;
        depth--;
        if (depth >= DEPTH) return;
        int s = stack[depth];
        if (s > 0 && counting()) {
            long dt = System.nanoTime() - startAt[depth];
            total[s] += dt;
            frameT[s] += dt;
            count[s]++;
        }
    }

    @Override
    public void popPush(String name) {
        pop();
        push(name);
    }

    @Override
    public void popPush(Supplier<String> name) {
        pop();
        push(name.get());
    }

    @Override public void startTick() {}
    @Override public void endTick() {}
    @Override public void markForCharting(MetricCategory category) {}
    @Override public void incrementCounter(String name, int amount) {}
    @Override public void incrementCounter(Supplier<String> name, int amount) {}

    private String path(int i) {
        return parent[i] <= 0 ? names[i] : path(parent[i]) + "/" + names[i];
    }

    /** the sections over 1 % of the window's frame time, in us a frame (the report, on the main thread: racy reads) */
    public static String report() {
        return report(INSTANCE);
    }

    public static String serverReport() {
        return report(SERVER);
    }

    private static String report(FrameProfile f) {
        long fr = Math.max(1, f.frames);
        long top = 0;
        for (int i = 1; i < f.n; i++) if (f.parent[i] == 0) top += f.total[i];
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "%s profile (%s, %d %s, top sections %.1f us a %s):", f.names[0], f.what, f.frames, f.unit, top / 1e3 / fr, f.names[0]));
        Integer[] idx = new Integer[f.n - 1];
        for (int i = 1; i < f.n; i++) idx[i - 1] = i;
        java.util.Arrays.sort(idx, (a, c) -> f.path(a).compareTo(f.path(c)));
        for (int i : idx) {
            if (f.total[i] * 100 < top) continue;
            b.append(String.format(java.util.Locale.ROOT, " %s %.1f (%.1f a %s);", f.path(i), f.total[i] / 1e3 / fr, (double) f.count[i] / fr, f.names[0]));
        }
        if (f.slowFrames > 0) {   // the slow frames: each section's us a slow frame next to its us an average frame
            long sf = f.slowFrames, stop = 0;
            for (int i = 1; i < f.n; i++) if (f.parent[i] == 0) stop += f.slowT[i];
            b.append(String.format(java.util.Locale.ROOT, " | slow %ss (over %.1f ms): %d, top sections %.1f us a slow %s:", f.names[0], SLOW_NS / 1e6, sf, stop / 1e3 / sf, f.names[0]));
            Integer[] by = new Integer[f.n - 1];
            for (int i = 1; i < f.n; i++) by[i - 1] = i;
            java.util.Arrays.sort(by, (a, c) -> Long.compare(f.slowT[c] - f.total[c] * sf / fr, f.slowT[a] - f.total[a] * sf / fr));
            for (int k = 0; k < Math.min(Integer.getInteger("lab.frameProfile.slowTop", 18), by.length); k++) {
                int i = by[k];
                b.append(String.format(java.util.Locale.ROOT, " %s %.1f (avg %.1f);", f.path(i), f.slowT[i] / 1e3 / sf, f.total[i] / 1e3 / fr));
            }
        }
        return b.toString();
    }
}
