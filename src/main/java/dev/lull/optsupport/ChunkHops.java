package dev.lull.optsupport;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;

/** ChunkHopsInTick (notes/92): the chunk sources' main-thread mailboxes run inside the tick, before "send chunks". */
public final class ChunkHops {
    public static final long BUDGET_NANOS = Long.getLong("lab.chunkHops.budgetMs", 10) * 1_000_000L;
    public static final LongAdder TASKS = new LongAdder(), NANOS = new LongAdder(), CUT = new LongAdder();

    private ChunkHops() {}

    /** server thread, inside tickChildren: what is queued now, no waiting, within the budget */
    public static void run(Iterable<ServerLevel> levels) {
        long t0 = System.nanoTime(), end = t0 + BUDGET_NANOS;
        int n = 0;
        outer:
        for (ServerLevel l : levels) {
            var cs = l.getChunkSource();
            while (cs.pollTask()) {
                n++;
                if (System.nanoTime() > end) {
                    CUT.increment();
                    break outer;
                }
            }
        }
        TASKS.add(n);
        NANOS.add(System.nanoTime() - t0);
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "chunk hops in tick: %d tasks, %.1f ms, %d budget cuts", TASKS.sumThenReset(), NANOS.sumThenReset() / 1e6, CUT.sumThenReset());
    }
}
