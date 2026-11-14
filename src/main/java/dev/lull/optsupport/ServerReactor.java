package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import dev.lab.runtime.OsThreads;

/**
 * The server's pool for work taken off the main thread (the counterpart of the client's FrameReactor): chunk save
 * encoding first; light, generation and chunk-tick jobs are to follow on the same workers.
 */
public final class ServerReactor {
    private ServerReactor() {}

    /** OnePool: the server's pool also takes vanilla's background work (world generation) - Worker-Main's workers join it */
    static final boolean ONE_POOL = Boolean.getBoolean("lab.opt.OnePool");
    private static final int THREADS = Integer.getInteger("lab.sreactor.threads", ONE_POOL ? Math.max(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)), LabPools.BG)
            : Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)));
    private static volatile LabRuntime pool;

    /** the server's pool if it was made (Sides: no pool made by asking) */
    public static LabRuntime poolIfMade() {
        return pool;
    }

    public static synchronized LabRuntime pool() {
        if (pool == null) {
            // OnePool: below normal like Worker-Main was (generation must not take the cores from the server and render
            // threads); the lanes order the work inside
            pool = ONE_POOL ? LabRuntime.Config.of("Server-Worker", THREADS).withPriority(OsThreads.BELOW_NORMAL).withDaemon(true).withTickPriority(OsThreads.NORMAL)
                    : new LabRuntime(LabRuntime.Config.of("Server-Worker", THREADS).withPriority(OsThreads.NORMAL).withDaemon(true));
            System.out.println("[sleep/opt] server reactor: " + THREADS + " workers" + (ONE_POOL ? " (one pool: vanilla's background work too)" : ""));
        }
        return pool;
    }
}
