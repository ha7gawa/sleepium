package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * -Dlab.opt.OnePool (1.3.0, step 1): vanilla's shared background executor (Util.backgroundExecutor, "Worker-Main")
 * becomes a router - a task goes to the pool of the side that gave it (Sides): the server's pool (world generation,
 * chunk tasks of the integrated or dedicated server) or the client's (resource loading, the client's chunk work). No
 * third pool of its own: the server's pool takes Worker-Main's workers. Lanes inside each pool keep tick fan-outs
 * ahead of this work. Shutdown is a no-op: the two pools live as long as the process (daemon threads).
 */
public final class SidePool extends AbstractExecutorService {
    @Override
    public void execute(Runnable task) {
        (Sides.server() ? ServerReactor.pool() : FrameReactor.pool()).execute(task, LabRuntime.Lane.CHUNK);
    }

    @Override public void shutdown() {}
    @Override public List<Runnable> shutdownNow() { return List.of(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
}
