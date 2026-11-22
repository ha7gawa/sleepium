package dev.lull.optsupport;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.util.thread.ProcessorMailbox;

/**
 * LightDirect's queue in front of a level's light mailbox (notes/84): the light tasks in vanilla's priority order -
 * lowest queue level first (the sorter's order: level 0 = structure changes vanilla marks urgent - updateSectionStatus,
 * queueSectionData, retainData, updateChunkStatus -, then the player's nearest chunks by ticket level), FIFO within a
 * level - without the sorter's per-chunk round trips over the worker pool.
 *
 * <p>The one dependency vanilla gets from the sorter's delay alone (11-22, 1.20.1): the light engine reads a chunk
 * through the VISIBLE chunk map (ServerChunkCache.getChunkForLighting); a holder created by a distance-manager update is
 * visible only once the main thread promotes the map after that update, yet the chunk's generation starts during the
 * update - a chunk loaded quickly (from the stored ring) reached initializeLight / lightChunk before the promotion, and
 * the light found no chunk: its sources unlit, its sky as if empty (fixcheck: 4-27 chunks a 1024-chunk area). Here a
 * task waits ("parked", still in its lane, its chunk's later tasks behind it) while its chunk is not visible and no
 * promotion of the chunk map (ChunkMap.promoteChunkMap, LightDirectPromoteMixin) has happened since it was submitted;
 * each promotion drains again. After one, a holder that existed at submission is visible; one still absent is really
 * absent (an unloaded chunk's last tasks) and the task runs as vanilla's would.
 *
 * <p>Every submitted task tells the mailbox a drain; a drain runs every ready task, lowest level first, one at a time,
 * on the light actor. The mailbox stays the only executor.
 */
public final class LightLanes {
    /** -Dlab.light.prio=false: plain FIFO (the first LightDirect prototype) */
    public static final boolean PRIO = !"false".equals(System.getProperty("lab.light.prio"));
    /** -Dlab.light.waitVisible=false: no waiting for the chunk map's promotion (the 11-21 LightLanes) */
    public static final boolean WAIT_VISIBLE = !"false".equals(System.getProperty("lab.light.waitVisible"));
    private static final int LEVELS = ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT;
    /** tasks queued here and not run yet, all levels (LightCheck's drain on 1.20.1) */
    public static final AtomicInteger PENDING = new AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicLong PARKED_SEEN = new java.util.concurrent.atomic.AtomicLong();
    private static final CopyOnWriteArrayList<LightLanes> ALL = new CopyOnWriteArrayList<>();

    /** a task and its chunk */
    private static final class Item {
        final long pos, epoch;
        final Runnable task;

        Item(long pos, long epoch, Runnable task) {
            this.pos = pos;
            this.epoch = epoch;
            this.task = task;
        }
    }

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Item>[] lanes = new ConcurrentLinkedQueue[LEVELS];
    private final Runnable drain = this::drain;
    private final Object owner;
    private final LongPredicate ready;
    private final ProcessorMailbox<Runnable> mailbox;
    /** a drain left tasks waiting for a promotion */
    private volatile boolean parked;
    /** promotions of the owner's chunk map so far */
    private volatile long epoch;

    /** owner: the chunk map (promotions are matched by it); ready: is this chunk visible to the light engine now */
    public LightLanes(Object owner, LongPredicate ready, ProcessorMailbox<Runnable> mailbox) {
        for (int i = 0; i < LEVELS; i++) lanes[i] = new ConcurrentLinkedQueue<>();
        this.owner = owner;
        this.ready = ready;
        this.mailbox = mailbox;
        ALL.add(this);
    }

    public void submit(int level, long pos, Runnable task) {
        PENDING.incrementAndGet();
        lanes[Math.max(0, Math.min(LEVELS - 1, level))].add(new Item(pos, epoch, task));
        mailbox.tell(drain);
    }

    private void drain() {
        boolean left = false;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet waiting = null;   // chunks found not ready in this drain: all their tasks wait (their order kept)
        outer:
        while (true) {
            for (ConcurrentLinkedQueue<Item> q : lanes) {
                for (Iterator<Item> it = q.iterator(); it.hasNext(); ) {
                    Item i = it.next();
                    // ready: its chunk visible, or the chunk map promoted since the task came (a holder that existed then is
                    // visible now - still absent = really absent: an unloaded chunk's last tasks run as vanilla's)
                    if (WAIT_VISIBLE && (waiting != null && waiting.contains(i.pos) || i.epoch == epoch && !ready.test(i.pos))) {
                        if (!left) PARKED_SEEN.incrementAndGet();
                        left = true;
                        if (waiting == null) waiting = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                        waiting.add(i.pos);
                        continue;
                    }
                    it.remove();
                    PENDING.decrementAndGet();
                    i.task.run();
                    continue outer;   // from the lowest level again: the task may have queued urgent ones
                }
            }
            break;
        }
        parked = left;
    }

    /** the main thread promoted a chunk map: lanes with waiting tasks drain again */
    public static void promoted(Object chunkMap) {
        for (LightLanes l : ALL)
            if (l.owner == chunkMap) {
                l.epoch++;
                if (l.parked) l.mailbox.tell(l.drain);
            }
    }
}
