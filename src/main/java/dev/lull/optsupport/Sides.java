package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;

/**
 * Whose work a thread does: the client's or the server's (1.3.0's two pools). The render thread and the client pool's
 * workers are the client; the server thread and the server pool's workers the server; any other thread serves the
 * integrated server while there is one, else the client (a dedicated server: always the server). Used to send a task
 * given to vanilla's shared background executor to the pool of the side it came from (OnePool), so the integrated
 * server's world generation runs on the server's pool and does not take the client's workers.
 */
public final class Sides {
    private Sides() {}

    private static final boolean DEDICATED = dedicated();
    /** the render thread (the client: set when Minecraft is made) */
    public static volatile Thread renderThread;

    private static boolean dedicated() {
        try {
            return !dev.lull.Loader.client();
        } catch (Throwable t) {
            return false;
        }
    }

    /** the current thread does the server's work */
    public static boolean server() {
        if (DEDICATED) return true;
        Thread t = Thread.currentThread();
        if (t == renderThread) return false;
        if (t instanceof LabRuntime.LabThread lt) {
            LabRuntime o = lt.owner();
            if (o == FrameReactor.poolIfMade()) return false;
            if (o == ServerReactor.poolIfMade()) return true;
        }
        return dev.lull.Loader.server != null;
    }
}
