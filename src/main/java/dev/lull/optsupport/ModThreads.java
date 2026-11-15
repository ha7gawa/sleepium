package dev.lull.optsupport;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The threads each mod made, for the Mods page (plan D, 11-15: mods' pools observed, not taken over). A thread belongs to
 * the mod whose code it was started to run - the first frame from the bottom of its stack that is not the JDK's, a pool
 * library's or the loader's, mapped through its class's module to the mod file (FML's ModList). Per mod: thread groups
 * (names without their number), live threads, how many sit idle (in a pool waiting for work, or parked / sleeping), and
 * the CPU time they used since they started. Asked by the screen; one census at most every 2 seconds (it walks every
 * thread's stack).
 */
public final class ModThreads {
    private ModThreads() {}

    public record Group(String name, int live, int idle, double cpuSeconds) {}

    public record Info(int live, int idle, double cpuSeconds, java.util.List<Group> groups) {
        public String line() {
            return String.format(java.util.Locale.ROOT, "%d (%d idle), CPU %.1f s", live, idle, cpuSeconds);
        }
    }

    private static Map<String, Info> last = Map.of();
    private static long lastAt;
    /** a module -> the mods it counts for: its own and the outermost jar's (a mod inside another's jar: C2ME's parts) */
    private static Map<String, String[]> moduleToMod;

    public static synchronized Info of(String mod) {
        long now = System.currentTimeMillis();
        if (now - lastAt > 2000) {
            last = census();
            lastAt = now;
        }
        return last.get(mod);
    }

    private static Map<String, String[]> modules() {
        if (moduleToMod == null) {
            Map<String, String[]> m = new HashMap<>();
            try {
                // (1.20.1: Forge's mod files know no parent jar - a mod inside another's counts for itself only)
                for (var f : net.minecraftforge.fml.ModList.get().getModFiles()) {
                    if (f.getMods().isEmpty()) continue;
                    String id = f.getMods().get(0).getModId();
                    String top = id;
                    try {
                        m.put(f.getFile().getSecureJar().name(), top.equals(id) ? new String[] {id} : new String[] {id, top});
                    } catch (Throwable ignored) {
                        // a mod file without a module name: found by nothing
                    }
                }
            } catch (Throwable ignored) {
                // no ModList yet
            }
            moduleToMod = m;
        }
        return moduleToMod;
    }

    private static final Pattern NUMBERED = Pattern.compile("^(.*?)[-# ]?(\\d+)$");

    private static Map<String, Info> census() {
        var mx = ManagementFactory.getThreadMXBean();
        Map<String, String[]> mods = modules();
        // mod -> group -> [live, idle, cpu ns]
        Map<String, Map<String, long[]>> by = new TreeMap<>();
        for (var e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            StackTraceElement[] st = e.getValue();
            String[] owners = modOf(st, mods);
            if (owners == null) continue;
            Matcher m = NUMBERED.matcher(t.getName());
            String group = (m.matches() ? m.group(1) : t.getName()).replaceAll("[\s#:_-]+$", "");
            boolean idle = false;
            for (StackTraceElement f : st) {
                String c = f.getClassName(), mth = f.getMethodName();
                if ((c.equals("java.util.concurrent.ThreadPoolExecutor") && mth.equals("getTask")) || (c.equals("java.util.concurrent.ForkJoinPool") && mth.equals("awaitWork")))
                    idle = true;
            }
            if (!idle && st.length > 0 && (t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING)) {
                String top = st[0].getClassName() + "." + st[0].getMethodName();
                if (top.contains("Unsafe.park") || top.contains("Thread.sleep") || top.contains("Object.wait")) idle = true;
            }
            long c = mx.getThreadCpuTime(t.getId());   // (Java 17)
            for (String mod : owners) {
                long[] v = by.computeIfAbsent(mod, k -> new TreeMap<>()).computeIfAbsent(group, k -> new long[3]);
                v[0]++;
                if (idle) v[1]++;
                if (c > 0) v[2] += c;
            }
        }
        Map<String, Info> out = new HashMap<>();
        for (var me : by.entrySet()) {
            java.util.List<Group> gs = new java.util.ArrayList<>();
            int live = 0, idle = 0;
            long cpu = 0;
            for (var ge : me.getValue().entrySet()) {
                long[] v = ge.getValue();
                gs.add(new Group(ge.getKey(), (int) v[0], (int) v[1], v[2] / 1e9));
                live += (int) v[0];
                idle += (int) v[1];
                cpu += v[2];
            }
            gs.sort((a, b) -> Double.compare(b.cpuSeconds(), a.cpuSeconds()));
            out.put(me.getKey(), new Info(live, idle, cpu / 1e9, gs));
        }
        return out;
    }

    /** the mod of the code a thread was made to run (null: the JDK's, the loader's, Minecraft's, or no mod) */
    private static String[] modOf(StackTraceElement[] st, Map<String, String[]> mods) {
        for (int i = st.length - 1; i >= 0; i--) {
            String c = st[i].getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.") || c.startsWith("io.netty.")
                    || c.startsWith("cpw.") || c.startsWith("net.neoforged.fml") || c.startsWith("org.lwjgl") || c.startsWith("com.google.")
                    || c.startsWith("it.unimi") || c.startsWith("dev.lab.runtime")) continue;
            try {
                Class<?> k = Class.forName(c, false, Thread.currentThread().getContextClassLoader());
                String mn = k.getModule().getName();
                return mn == null ? null : mods.get(mn);
            } catch (Throwable ex) {
                return null;
            }
        }
        return null;
    }
}
