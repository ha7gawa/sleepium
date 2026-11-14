package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import dev.lab.runtime.OsThreads;
import java.util.concurrent.ExecutorService;

/**
 * Pool factories for LabPoolsMixin: vanilla's worker pools replaced by {@link LabRuntime} (persistent threads,
 * woken on demand, never created on demand).
 * <ul>
 *   <li>background ("Worker-Main"): {@code lab.opt.bgThreads} workers (default 4; with ParallelWorldgen half the cores, 4 to 8), BELOW_NORMAL, so the server
 *       thread and the OS win any contention;</li>
 *   <li>IO ("IO-Worker"): {@code lab.opt.ioThreads} (default 4), ABOVE_NORMAL: disk and network wait on the OS, and
 *       when their data arrives they should run at once;</li>
 *   <li>Download: 2 workers, ABOVE_NORMAL, daemon (as vanilla).</li>
 * </ul>
 * Thread names keep vanilla's prefixes, so profiles and thread dumps stay comparable.
 */
public final class LabPools {
    /** the background workers: 4; with ParallelWorldgen (its steps wait for a worker with 4: notes/41) half the cores, 4 to 8 -
     *  8 here (16 cores), 4 on a friend's 8-core machine; lab.opt.bgThreads sets it */
    static final int BG = Integer.getInteger("lab.opt.bgThreads", Boolean.getBoolean("lab.opt.ParallelWorldgenMixin")
            ? Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)) : 4);
    private static final int IO = Integer.getInteger("lab.opt.ioThreads", 4);

    private LabPools() {}

    public static ExecutorService background(String serviceName, Thread.UncaughtExceptionHandler handler) {
        if (Boolean.getBoolean("lab.opt.OnePool") && "Main".equals(serviceName)) {
            System.out.println("[sleep/opt] one pool: vanilla's background work goes to the client's or the server's pool, by the side that gives it");
            return new SidePool();
        }
        LabRuntime rt = new LabRuntime(LabRuntime.Config.of("Worker-" + serviceName, BG)
                .withPriority(OsThreads.BELOW_NORMAL).withHandler(handler));
        System.out.println("[sleep/opt] LabRuntime background pool 'Worker-" + serviceName + "': " + BG
                + " workers, BELOW_NORMAL, native priorities: " + OsThreads.nativeAvailable());
        // commonPool cannot be redirected: its only user is NeoForge's bootstrap (JarModuleFinder parallel stream),
        // which runs before any mod or mixin exists. Cap it with a JVM flag instead.
        int cp = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism();
        if (cp > 2) System.out.println("[sleep/opt] commonPool parallelism " + cp
                + ": add -Djava.util.concurrent.ForkJoinPool.common.parallelism=2 (bootstrap spawns " + cp + " idle threads)");
        return rt;
    }

    /** OnePool: one group of IO workers for every vanilla IO pool (IO-Worker, Download): blocking work, regulated by the OS */
    private static LabRuntime sharedIo;

    public static ExecutorService io(String name, boolean daemon, Thread.UncaughtExceptionHandler handler) {
        String base = name.endsWith("-") ? name.substring(0, name.length() - 1) : name;
        if (Boolean.getBoolean("lab.opt.OnePool")) {
            synchronized (LabPools.class) {
                if (sharedIo == null) {
                    sharedIo = new LabRuntime(LabRuntime.Config.of("IO-Worker", IO).withPriority(OsThreads.ABOVE_NORMAL).withDaemon(true).withHandler(handler));
                    System.out.println("[sleep/opt] one pool: one IO group (" + IO + " workers) for vanilla's IO pools");
                }
                System.out.println("[sleep/opt] one pool: '" + base + "' -> the IO group");
                return new SidePool.Shared(sharedIo);
            }
        }
        int n = base.startsWith("Download") ? 2 : IO;
        LabRuntime rt = new LabRuntime(LabRuntime.Config.of(base, n)
                .withPriority(OsThreads.ABOVE_NORMAL).withDaemon(daemon).withHandler(handler));
        System.out.println("[sleep/opt] LabRuntime IO pool '" + base + "': " + n + " workers, ABOVE_NORMAL, daemon " + daemon);
        return rt;
    }
}
