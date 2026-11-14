package dev.lull.optsupport.sleep;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The sleep engines' per-class bytecode analysis (SleepAnalysis) off the server thread. It ran at the first tick of
 * every new entity or block entity class, on the server thread: 60-200 ms stalls the first time a cat, an iron golem,
 * a turtle or a vault was met (10-08, slow-tick sampling). Here the first meeting starts the analysis on one
 * background thread (one: SleepAnalysis is never run from two threads at once) and the object ticks as vanilla does
 * until the result is there; then its sleeper is made as before. A class whose analysis fails keeps ticking as vanilla.
 */
final class AsyncAnalysis {
    private AsyncAnalysis() {}

    /** OnePool: a strand on the server's pool, BACKGROUND lane (one at a time, no thread of its own); else a thread */
    private static final ExecutorService EXEC = Boolean.getBoolean("lab.opt.OnePool")
            ? new dev.lab.runtime.Strand("sleep-analysis", r -> dev.lull.optsupport.ServerReactor.pool().execute(r, dev.lab.runtime.LabRuntime.Lane.BACKGROUND))
            : Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sleep-sleep-analysis");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final Set<Object> PENDING = ConcurrentHashMap.newKeySet(), FAILED = ConcurrentHashMap.newKeySet();

    /** the result for key if there is one; else its computation is started (once) in the background and null returned */
    static <K, V> V get(Map<K, V> done, K key, Function<K, V> compute) {
        V v = done.get(key);
        if (v != null || FAILED.contains(key)) return v;
        if (PENDING.add(key)) {
            EXEC.execute(() -> {
                try {
                    done.put(key, compute.apply(key));
                } catch (Throwable t) {
                    FAILED.add(key);
                    System.out.println("[sleep/sleep] analysis of " + key + " failed, it ticks as vanilla: " + t);
                } finally {
                    PENDING.remove(key);
                }
            });
        }
        return null;
    }
}
