package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import dev.lab.runtime.MpmcRing;
import dev.lab.runtime.OsThreads;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * exp/frame-reactor (client): the render thread as a reactor for the frame's background work.
 *
 * <ul>
 *   <li>one LabRuntime for the frame's jobs (parallel entity rendering, the light job): workers persistent, woken on
 *       demand;</li>
 *   <li>a completion ring (Vyukov MPMC, preallocated, no locks): a worker hands back "what the render thread must do
 *       with my result" (replay section marks, …); the render thread runs those at each point it reaches;</li>
 *   <li>points of the frame where participants must have their results or must not be running (the light point, the
 *       end of renderLevel, before queued packets, before the tick, a level change): each participant decides there
 *       whether to wait, and the render thread helps the pool while it waits where the participant allows it.</li>
 * </ul>
 * Jobs keep their own ordering rules (ParallelRender appends in entity order); the reactor gives them the pool, the
 * completion path and the points.
 */
public final class FrameReactor {
    private FrameReactor() {}

    public enum Point { LIGHT, END_OF_LEVEL, BEFORE_TASKS, BEFORE_TICK, LEVEL_CHANGE }

    /** Something with background work and rules for when it must be finished. */
    public interface Participant {
        void reach(Point p);
    }

    private static final int THREADS = Integer.getInteger("lab.reactor.threads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)));
    private static volatile LabRuntime pool;
    private static final MpmcRing DONE = new MpmcRing(1024);
    private static final ArrayDeque<Runnable> OVERFLOW = new ArrayDeque<>();
    private static final List<Participant> PARTICIPANTS = new ArrayList<>();
    public static long completions, overflows;

    public static int threads() {
        return THREADS;
    }

    /** the client's pool if it was made (Sides: no pool made by asking) */
    public static LabRuntime poolIfMade() {
        return pool;
    }

    public static synchronized LabRuntime pool() {
        if (pool == null) {
            if (Boolean.getBoolean("lab.opt.OnePool")) {
                // one pool: the client's background work too (chunk meshes, resource loading), below normal for that work,
                // normal while the workers help a frame (TICK work). Its own size, not Worker-Main's: 8 meshing workers
                // finished more sections a frame than the render thread uploads smoothly (11-14 host: stutters 0.58 -> 0.84 %,
                // with 4: 0.49-0.69 %); the server's pool takes Worker-Main's workers (generation)
                int n = THREADS;
                pool = LabRuntime.Config.of("Frame-Worker", n).withPriority(OsThreads.BELOW_NORMAL).withDaemon(true).withTickPriority(OsThreads.NORMAL);
                System.out.println("[sleep/opt] frame reactor: " + n + " workers (one pool: the client's background work too)");
                return pool;
            }
            pool = new LabRuntime(LabRuntime.Config.of("Frame-Worker", THREADS).withPriority(OsThreads.NORMAL).withDaemon(true));
            System.out.println("[sleep/opt] frame reactor: " + THREADS + " workers");
        }
        return pool;
    }

    public static void register(Participant p) {
        if (!PARTICIPANTS.contains(p)) PARTICIPANTS.add(p);
    }

    /** From any thread: run r on the render thread at the next point it reaches. */
    public static void complete(Runnable r) {
        if (!DONE.offer(r)) {
            synchronized (OVERFLOW) {
                OVERFLOW.add(r);
            }
            overflows++;
        }
    }

    /**
     * From any thread: r makes GL calls - on the render thread at its next point, or, with the frame thread
     * (FrameThread: the context lives there), at that thread's next frame job (N100 tour 11-16: LightVolumes' upload
     * drained on the main thread without a context, a native abort)
     */
    public static void completeGl(Runnable r) {
        complete(() -> {
            if (!FrameThread.deferRun(r)) r.run();
        });
    }

    /** Render thread: the completions handed back so far. */
    public static void drain() {
        Object r;
        while ((r = DONE.poll()) != null) {
            ((Runnable) r).run();
            completions++;
        }
        if (overflows > 0) {
            synchronized (OVERFLOW) {
                Runnable x;
                while ((x = OVERFLOW.poll()) != null) {
                    x.run();
                    completions++;
                }
            }
        }
    }

    /** Render thread: a point of the frame. Completions first, then every participant's rule for this point. */
    public static void reach(Point p) {
        drain();
        // a level change (disconnect, a new level) outside runTick: TickAsync's walk joined first - its workers may wait
        // for the render thread's answers (ParticleWorkers' shapes), and a participant's wait (the light job, queued
        // behind them on the same pool) would never end (N100 tour 11-16: the game hung on quitting)
        if (p == Point.LEVEL_CHANGE) {
            FrameThread.holdUntilNextFrame();   // (no extra frames of the old level while it is taken down)
            TickAsync.join();
        }
        for (int i = 0; i < PARTICIPANTS.size(); i++) PARTICIPANTS.get(i).reach(p);
        drain();
    }

    public static String report() {
        return "frame reactor: " + THREADS + " workers, completions " + completions + " (ring overflows " + overflows + ")";
    }
}
