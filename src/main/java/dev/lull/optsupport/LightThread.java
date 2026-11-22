package dev.lull.optsupport;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** LightDirect's own light thread (-Dlab.light.ownThread=true): one thread for the levels' light mailboxes, parked when idle. */
public final class LightThread {
    private LightThread() {}

    public static final boolean ON = Boolean.getBoolean("lab.light.ownThread");
    public static final Executor EXEC = ON ? new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "Sleepium light");
        t.setDaemon(true);
        return t;
    }) : null;
}
