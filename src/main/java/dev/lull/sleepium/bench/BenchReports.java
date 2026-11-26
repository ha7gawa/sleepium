package dev.lull.sleepium.bench;

/** The bench line's counters of the optimizations that exist so far. */
public final class BenchReports {
    private BenchReports() {}

    public static String all() {
        return dev.lull.sleepium.server.TickIndex.report() + "; " + dev.lull.sleepium.gl.Capture.report() + "; GL redirect: " + dev.lull.sleepium.core.GlRedirect.CLASSES + " classes";
    }
}
