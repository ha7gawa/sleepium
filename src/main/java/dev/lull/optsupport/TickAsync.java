package dev.lull.optsupport;

import java.util.IdentityHashMap;
import net.minecraft.world.entity.Entity;

/**
 * -Dlab.opt.TickAsyncMixin=true (plan D, stage 1, 11-15): the client tick's entity walk off the frame's critical path.
 * Entities drawn from tick poses (their type qualified, a usable GPU pair) are left out of ClientLevel's walk; at the end
 * of Minecraft.tick they tick on the pool while the render thread goes on to draw the frame, and each worker captures
 * the entity's new pair right after its tick (into the entity's spare pair). Meanwhile frames draw these entities from
 * their current pair held at its last key (partial tick 1: the state the tick started from - right after a tick the
 * frame's own partial tick is near 0, so that is where vanilla would draw them too); no renderer reads them live. The
 * walk is joined at the next runTick's start (or the next client tick's), before packets: the deferred section moves and
 * removals applied in the walk's order, the pairs swapped. The other entities tick in the walk as before.
 *
 * <p>Not exact: these entities tick after the rest of the client tick instead of in their slot (ParallelEntities'
 * conflict hooks refuse a class whose tick touches what belongs to the render thread or sees another walking entity).
 */
public final class TickAsync {
    private TickAsync() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.TickAsyncMixin");
    /** ClientAuto's in-window flip */
    public static boolean enabled = true;
    /** the entities ticking on the pool now (render thread only) */
    /** value: Boolean.TRUE (drawn from its tick pair) or the snapshot job (drawn from its pre-tick snapshot) */
    /** (concurrent: the main thread fills it in the tick while an overlapped frame reads it - the frame thread audit, 11-16) */
    static final java.util.concurrent.ConcurrentHashMap<Entity, Object> WALKING = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * -Dlab.tickAsync.snap (default on): stage 2 - entities without tick poses that the last frames drew on a worker:
     * the worker renders a snapshot at partial tick 1 (camera at the entity: parts, vertices, flame, shadow) before it
     * ticks the entity; frames meanwhile draw the snapshot moved to their camera.
     */
    static final boolean SNAP = !"false".equals(System.getProperty("lab.tickAsync.snap"));
    public static long snapshots, snapFrames, unseen;
    /**
     * -Dlab.tickAsync.unseen (default on): entities no frame drew in the last two - nothing to hold for them; one that
     * comes into view while the walk runs misses that frame (counted in skipped) instead of being read mid-tick
     */
    static final boolean UNSEEN = !"false".equals(System.getProperty("lab.tickAsync.unseen"));

    /** render thread, the walk: not drawn in the last frames */
    static boolean unseenEligible(Entity e) {
        return enabled && UNSEEN && e.tickCount > 2 && !(e instanceof net.minecraft.world.entity.player.Player) && !ParallelRender.drawnLately(e);   // (a new one is about to be drawn)
    }
    public static long walks, entities, holdFrames, skipped, joinWaitNs, joins;
    /** the skips by cause: no pair nor snapshot at the queue, the snapshot not drawn, the held pair's merge failed */
    public static long skipNone, skipSnap, skipMerge;

    /** render thread, the walk: whether this entity ticks on the pool after the client tick */
    static boolean eligible(Entity e) {
        return enabled && TickPoses.ON && !(e instanceof net.minecraft.world.entity.player.Player) && TickPoses.asyncReady(e);
    }

    /** render thread, the walk: drawn on a worker in the last frames - a pre-tick snapshot can stand in for it */
    static boolean snapEligible(Entity e) {
        return enabled && SNAP && !(e instanceof net.minecraft.world.entity.player.Player) && ParallelRender.drewOnWorker(e);
    }

    /** render thread: the walking entity's snapshot job (null: drawn from a pair, or not walking) */
    public static ParallelRender.Job snapshotOf(Entity e) {
        Object v = WALKING.isEmpty() ? null : WALKING.get(e);
        return v instanceof ParallelRender.Job j ? j : null;
    }

    public static long unseenWaits;

    /**
     * render thread, a frame queuing a walking entity that was taken as unseen: its tick waited for (then it is drawn the
     * usual way - its fields hold the new tick, the frame's partial tick near 0: as vanilla); false for any other entity
     */
    public static boolean awaitUnseen(Entity e) {
        Object v = WALKING.get(e);
        if (!(v instanceof Integer i)) return false;
        ParallelEntities.awaitTicked(i);
        unseenWaits++;
        return true;
    }

    /** render thread: the entity is ticking on the pool - frames draw it only from its held pair */
    public static boolean holding(Entity e) {
        return !WALKING.isEmpty() && WALKING.containsKey(e);
    }

    /** Minecraft.tick returned: the left-out entities go to the pool */
    /**
     * -Dlab.tickAsync.giveWayMs=W (2; 0 = never): the walk gives way while its joins wait long for the frame thread -
     * a frame drawing at the join is waited for whole (its snapshots and pairs are in use), and where frames are long
     * (Distant Horizons' LODs: ~28 ms on the host's tour) that costs more than the walk saves (102 vs 110 fps without
     * it). When the joins' average wait passes W, entities tick in the tick again for GIVE_WAY_TICKS, then it tries again.
     */
    static final double GIVE_WAY_MS = Double.parseDouble(System.getProperty("lab.tickAsync.giveWayMs", "2"));
    static final int GIVE_WAY_TICKS = Integer.getInteger("lab.tickAsync.giveWayTicks", 200);
    private static double waitEma;
    private static int pausedTicks;
    public static long gaveWay;

    /**
     * -Dlab.tickAsync.gen (default on; the pipeline's generations, notes/75): the join's render part without stopping the frame thread. The pairs are
     * swapped, the walking set emptied and a new generation published (FrameThread.publish); a frame begun before it may
     * still draw from the old pairs and the snapshot jobs - those are written again only by the next walk, so the wait
     * moves to the next walk's start (the gate, tickEnd) and covers only a frame begun before the join that still draws:
     * never the frames queued in the ring behind it. The snapshot jobs come from two pools in turn and are let go there.
     */
    static final boolean GEN = !"false".equals(System.getProperty("lab.tickAsync.gen"));
    /** the generation published at the last join whose gate has not been passed (0: none) */
    private static long gateGen;
    public static long gates, gateWaits, gateWaitNs;

    /** before the next walk writes into the spare pairs and reuses snapshot jobs: no frame begun before the join draws */
    private static void gate() {
        long g = gateGen;
        if (g == 0) return;
        gateGen = 0;
        long t0 = System.nanoTime();
        if (FrameThread.awaitDrawnSince(g)) gateWaits++;
        long w = System.nanoTime() - t0;
        gateWaitNs += w;
        gates++;
        ParallelEntities.releaseRetired();
        giveWay(w);
    }

    public static void tickEnd() {
        if (pausedTicks > 0 && --pausedTicks == 0) {   // try again, measured afresh
            enabled = true;
            waitEma = 0;
        }
        gate();
        ParallelEntities.startAsync();
    }


    /** runTick's / Minecraft.tick's start: the walk joined (help, then wait) */
    public static void join() {
        if (!ParallelEntities.asyncRunning()) return;
        long t0 = System.nanoTime();
        ParallelEntities.joinAsyncWorld();   // (the frame thread may draw meanwhile: nothing it reads changes here)
        finishRender();
        joinWaitNs += System.nanoTime() - t0;
        joins++;
    }

    /**
     * runTick's start with self-clocked frames: joined only if the walk is done (the main thread wakes every few ms for
     * packets - a join there waited for a walk just begun; the walk's end wakes it instead: FrameThread.walkDone)
     */
    public static void joinIfDone() {
        if (ParallelEntities.asyncRunning() && ParallelEntities.asyncFinished()) join();
    }

    public static long tasksDeferred;

    /**
     * runTick's tasks (TickAsyncMixin): may they run now? Not while the walk ticks entities - joined here if it is done
     * or a tick is due anyway; otherwise the tasks wait (counted)
     */
    public static boolean tasksMayRun(boolean tickDue) {
        if (!ParallelEntities.asyncRunning()) return true;
        if (tickDue || ParallelEntities.asyncFinished()) {
            join();
            return true;
        }
        tasksDeferred++;
        return false;
    }

    /** a level change: the walk joined whole, its render part too */
    public static void joinAll() {
        if (ParallelEntities.asyncRunning()) {
            ParallelEntities.joinAsyncWorld();
            finishRender(false);
        }
        if (gateGen != 0) {   // (a level change: the frames drained first - FrameThread.holdUntilNextFrame)
            FrameThread.holdForMain();
            try {
                gateGen = 0;
                ParallelEntities.releaseRetired();
            } finally {
                FrameThread.releaseForMain();
            }
        }
    }

    /**
     * the render part: the frame thread may be drawing from this walk's snapshots and pairs - wait until no frame draws,
     * and no extra frame starts until it is done
     */
    private static void finishRender() {
        finishRender(GEN);
    }

    private static void finishRender(boolean gen) {
        if (gen && FrameThread.active) {   // no hold: swapped, then published (a frame begun from now on reads the new state)
            ParallelEntities.joinAsyncRender(true);
            gateGen = FrameThread.publish();
            return;
        }
        long t0 = System.nanoTime();
        FrameThread.holdForMain();
        long w = System.nanoTime() - t0;
        try {
            ParallelEntities.joinAsyncRender();
        } finally {
            FrameThread.releaseForMain();
        }
        giveWay(w);
    }

    /** the joins' (or the gates') waits for the frame thread, averaged: past GIVE_WAY_MS the walk gives way a while */
    private static void giveWay(long w) {
        if (GIVE_WAY_MS > 0 && FrameThread.active) {
            waitEma += (w / 1e6 - waitEma) * 0.1;
            if (waitEma > GIVE_WAY_MS && enabled) {   // (between ticks: the next tick's split sees it)
                enabled = false;
                pausedTicks = GIVE_WAY_TICKS;
                gaveWay++;
            }
        }
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "tick async: walks %d, entities %d (snapshots %d, unseen %d - waited for in a frame %d), frames holding %d (from snapshots %d), skipped %d, joins %d (%.3f ms a join; gave way %d times; client tasks waited for the walk %d times)",
                walks, entities, snapshots, unseen, unseenWaits, holdFrames, snapFrames, skipped, joins, joins == 0 ? 0.0 : joinWaitNs / 1e6 / joins,
                gaveWay, tasksDeferred)
                + String.format(java.util.Locale.ROOT, "; skips: no pair %d, snapshot failed %d, pair merge failed %d (held pairs on the CPU after a GPU refusal %d, refusals marked %d)",
                        skipNone, skipSnap, skipMerge, TickPoses.HELD_CPU.get(), TickPoses.HELD_REFUSED.get())
                + (GEN ? String.format(java.util.Locale.ROOT, "; generation gates %d (waited at %d, %.3f ms a gate)", gates, gateWaits, gates == 0 ? 0.0 : gateWaitNs / 1e6 / gates) : "");
    }
}
