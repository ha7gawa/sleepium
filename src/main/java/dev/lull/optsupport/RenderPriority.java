package dev.lull.optsupport;

import dev.lab.runtime.OsThreads;

/**
 * lab.opt.RenderPriorityMixin (client): the render thread above the other threads of the game, and the singleplayer
 * server's thread with it (-Dlab.render.serverPriority=false: the render thread only).
 *
 * <p>On a 4-core machine (Intel N100) the render thread lost its core to threads of the same priority - at the end of
 * every frame (runTick's Thread.yield, on Windows SwitchToThread) and in between - and slow frames sat descheduled with
 * the thread hardly on a CPU (the voxel session's thread audit, 11-09). Above normal: an N100 flight 85 -> 95 fps,
 * stutters 7.92 -> 1.33 %, p99 49.6 -> 21.4 ms. Windows: SetThreadPriority; macOS: the user-interactive QoS class;
 * Linux: raising needs privileges (usually refused) - there our pools step back instead (OsThreads: nice +5).
 */
public final class RenderPriority {
    private RenderPriority() {}

    private static boolean renderDone;
    public static final boolean SERVER = !"false".equals(System.getProperty("lab.render.serverPriority"));

    /** Minecraft.runTick, on the render thread: once */
    public static void render() {
        if (renderDone) return;
        renderDone = true;
        boolean ok = OsThreads.setCurrentPriority(OsThreads.ABOVE_NORMAL);
        System.out.println("[sleep/opt] render thread priority above normal: " + (ok ? "set" : "not taken by the OS (it stays normal)"));
    }

    /** IntegratedServer.initServer, on the singleplayer server's thread */
    public static void server() {
        if (!SERVER) return;
        boolean ok = OsThreads.setCurrentPriority(OsThreads.ABOVE_NORMAL);
        System.out.println("[sleep/opt] singleplayer server thread priority above normal: " + (ok ? "set" : "not taken by the OS (it stays normal)"));
    }
}
