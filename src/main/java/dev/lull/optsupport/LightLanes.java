package dev.lull.optsupport;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.util.thread.ProcessorMailbox;

/**
 * LightDirect's queue in front of a level's light mailbox (notes/84): the light tasks in vanilla's priority order -
 * lowest queue level first (the sorter's order: level 0 = structure changes vanilla marks urgent - updateSectionStatus,
 * queueSectionData, retainData, updateChunkStatus -, then the player's nearest chunks by ticket level), FIFO within a
 * level - without the sorter's per-chunk round trips over the worker pool.
 *
 * <p>Why the levels matter (11-21, N100 run ngd3): with one FIFO a feature's updateSectionStatus (a block written into an
 * empty section of a neighbour) waited behind earlier lightChunk tasks; the neighbour's light was propagated before the
 * new section existed, and a new block-light section starts dark (vanilla never pulls light into it) - 2 chunks, 269
 * nibbles below the from-scratch light. Vanilla gives those tasks level 0 so they overtake everything queued.
 *
 * <p>Every submitted task tells the mailbox one drain; a drain runs exactly one task, the first of the lowest non-empty
 * level at the moment it runs. The task is queued before its drain is told, so a drain always finds one. The mailbox
 * stays the only executor: the tasks still run one at a time, on the light actor.
 */
public final class LightLanes {
    /** -Dlab.light.prio=false: plain FIFO (the first LightDirect prototype) */
    public static final boolean PRIO = !"false".equals(System.getProperty("lab.light.prio"));
    private static final int LEVELS = ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT;

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Runnable>[] lanes = new ConcurrentLinkedQueue[LEVELS];
    private final Runnable drain = this::runOne;
    /** tasks queued here and not run yet, all levels (LightCheck's drain on 1.20.1) */
    public static final java.util.concurrent.atomic.AtomicInteger PENDING = new java.util.concurrent.atomic.AtomicInteger();

    public LightLanes() {
        for (int i = 0; i < LEVELS; i++) lanes[i] = new ConcurrentLinkedQueue<>();
    }

    public void submit(int level, Runnable task, ProcessorMailbox<Runnable> mailbox) {
        PENDING.incrementAndGet();
        lanes[Math.max(0, Math.min(LEVELS - 1, level))].add(task);
        mailbox.tell(drain);
    }

    private void runOne() {
        for (ConcurrentLinkedQueue<Runnable> q : lanes) {
            Runnable r = q.poll();
            if (r != null) {
                PENDING.decrementAndGet();
                r.run();
                return;
            }
        }
    }
}
