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
    public static final FrameProfile INSTANCE = new FrameProfile();

    private static final int MAX = 512, DEPTH = 64;
    private final String[] names = new String[MAX];
    private final int[] parent = new int[MAX];
    private final long[] total = new long[MAX], count = new long[MAX];
    private int n = 1;   // (slot 0: the root)
    private final int[] stack = new int[DEPTH];
    private final long[] startAt = new long[DEPTH];
    private int depth;
    private long frames;

    private FrameProfile() {
        names[0] = "frame";
        parent[0] = -1;
    }

    /** the window's frames counted (the frame thread's GameRenderer.render calls) */
    public static void frame() {
        INSTANCE.depth = 0;   // (a frame starts at the root: a section left open by another mod's early return ends here)
        if (dev.lull.ClientMeter.measuring()) INSTANCE.frames++;
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
        if (s > 0 && dev.lull.ClientMeter.measuring()) {
            total[s] += System.nanoTime() - startAt[depth];
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
        FrameProfile f = INSTANCE;
        long fr = Math.max(1, f.frames);
        long top = 0;
        for (int i = 1; i < f.n; i++) if (f.parent[i] == 0) top += f.total[i];
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "frame profile (the frame thread, %d frames, top sections %.1f us a frame):", f.frames, top / 1e3 / fr));
        Integer[] idx = new Integer[f.n - 1];
        for (int i = 1; i < f.n; i++) idx[i - 1] = i;
        java.util.Arrays.sort(idx, (a, c) -> f.path(a).compareTo(f.path(c)));
        for (int i : idx) {
            if (f.total[i] * 100 < top) continue;
            b.append(String.format(java.util.Locale.ROOT, " %s %.1f (%.1f a frame);", f.path(i), f.total[i] / 1e3 / fr, (double) f.count[i] / fr));
        }
        return b.toString();
    }
}
