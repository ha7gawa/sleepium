package dev.lull.optsupport;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ResultField;

/** ClientTickProfileMixin's sums: nanoseconds per profiler path over all client ticks (render thread). */
public final class ClientTickProfile {
    private ClientTickProfile() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.ClientTickProfileMixin");
    private static final Map<String, long[]> SUMS = new TreeMap<>();
    private static long ticks, totalNs;

    public static void add(ProfileResults r) {
        if (!dev.lull.ClientMeter.measuring()) return;   // (the measured window only: world loading ticks inside its own sections)
        long ns = r.getNanoDuration();
        ticks++;
        totalNs += ns;
        walk(r, "root", "", ns, 0);
    }

    private static void walk(ProfileResults r, String path, String name, long rootNs, int depth) {
        if (depth > 7) return;
        List<ResultField> fields = r.getTimes(path);
        for (int i = 1; i < fields.size(); i++) {
            ResultField f = fields.get(i);
            String n = name.isEmpty() ? f.name : name + "/" + f.name;
            long[] s = SUMS.computeIfAbsent(n, k -> new long[2]);
            s[0] += (long) (f.globalPercentage * rootNs / 100.0);
            s[1] += f.count;
            if (!f.name.equals("unspecified")) walk(r, path + '\u001e' + f.name, n, rootNs, depth + 1);
        }
    }

    /** sections with 2 % of the client tick or more, ms per tick */
    /** the recording profiler seen only by the thread that made it (other threads - parallel entity ticks - tick in the same sections) */
    public static net.minecraft.util.profiling.ProfilerFiller gate(net.minecraft.util.profiling.ProfilerFiller p) {
        Thread owner = Thread.currentThread();
        return new net.minecraft.util.profiling.ProfilerFiller() {
            private boolean mine() {
                return Thread.currentThread() == owner;
            }

            @Override
            public void startTick() {
                if (mine()) p.startTick();
            }

            @Override
            public void endTick() {
                if (mine()) p.endTick();
            }

            @Override
            public void push(String name) {
                if (mine()) p.push(name);
            }

            @Override
            public void push(java.util.function.Supplier<String> name) {
                if (mine()) p.push(name);
            }

            @Override
            public void pop() {
                if (mine()) p.pop();
            }

            @Override
            public void popPush(String name) {
                if (mine()) p.popPush(name);
            }

            @Override
            public void popPush(java.util.function.Supplier<String> name) {
                if (mine()) p.popPush(name);
            }

            @Override
            public void markForCharting(net.minecraft.util.profiling.metrics.MetricCategory c) {
                if (mine()) p.markForCharting(c);
            }

            @Override
            public void incrementCounter(String name, int n) {
                if (mine()) p.incrementCounter(name, n);
            }

            @Override
            public void incrementCounter(java.util.function.Supplier<String> name, int n) {
                if (mine()) p.incrementCounter(name, n);
            }
        };
    }

    public static String report() {
        if (ticks == 0) return "client tick profile: no ticks";
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "client tick profile: %d ticks, %.3f ms a tick;", ticks, totalNs / 1e6 / ticks));
        for (Map.Entry<String, long[]> e : SUMS.entrySet()) {
            long ns = e.getValue()[0];
            if (ns * 50 < totalNs) continue;
            b.append(String.format(java.util.Locale.ROOT, " %s %.3f;", e.getKey(), ns / 1e6 / ticks));
        }
        return b.toString();
    }
}
