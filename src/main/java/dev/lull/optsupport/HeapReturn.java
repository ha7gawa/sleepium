package dev.lull.optsupport;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import java.lang.management.ManagementFactory;

/**
 * lab.opt.HeapReturn: G1 gives memory back to the OS after a burst (generation, a teleport, loading) instead of keeping
 * the peak committed. G1 grows the heap with the allocation rate and shrinks it only after a concurrent mark or a full
 * GC, judged by Min/MaxHeapFreeRatio (40 / 70 by default: up to 70 % of the committed heap may stay free); with no GC
 * while the game idles, nothing is returned (10-01: 525 MB live in 1.73 GB committed).
 *
 * <p>The three flags are "manageable": set at run time through HotSpotDiagnosticMXBean, so a jar needs no launcher
 * arguments. Only flags the player left at their default are touched (a -XX on the command line wins):
 * <ul>
 *   <li>G1PeriodicGCInterval = {@code lab.heap.periodicMs} (30 000): after that long without any GC, G1 starts a
 *       concurrent cycle (concurrent: no stop-the-world full GC), whose remark may uncommit;</li>
 *   <li>MaxHeapFreeRatio = {@code lab.heap.maxFree} (30), MinHeapFreeRatio = {@code lab.heap.minFree} (10): keep at
 *       most 30 % of the heap free after a cycle.</li>
 * </ul>
 * Not on other collectors (ZGC / Shenandoah uncommit by themselves); not under G1PeriodicGCSystemLoadThreshold rules.
 */
public final class HeapReturn {
    private HeapReturn() {}

    public static String apply() {
        if (!ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("UseG1GC")
                && ManagementFactory.getGarbageCollectorMXBeans().stream().noneMatch(b -> b.getName().startsWith("G1"))) return "heap return: not G1, left alone";
        HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        StringBuilder sb = new StringBuilder("heap return:");
        // Min before Max: the JVM rejects a MaxHeapFreeRatio below the current MinHeapFreeRatio
        set(hs, "MinHeapFreeRatio", Integer.getInteger("lab.heap.minFree", 10), sb);
        set(hs, "MaxHeapFreeRatio", Integer.getInteger("lab.heap.maxFree", 30), sb);
        set(hs, "G1PeriodicGCInterval", Long.getLong("lab.heap.periodicMs", 30_000L), sb);
        return sb.toString();
    }

    private static void set(HotSpotDiagnosticMXBean hs, String name, long value, StringBuilder sb) {
        try {
            VMOption o = hs.getVMOption(name);
            if (o.getOrigin() != VMOption.Origin.DEFAULT) {
                sb.append(' ').append(name).append(" kept ").append(o.getValue()).append(" (").append(o.getOrigin()).append(");");
                return;
            }
            hs.setVMOption(name, Long.toString(value));
            sb.append(' ').append(name).append(' ').append(o.getValue()).append(" -> ").append(value).append(';');
        } catch (Exception e) {
            sb.append(' ').append(name).append(" failed: ").append(e.getMessage()).append(';');
        }
    }
}
