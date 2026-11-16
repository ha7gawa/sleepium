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
    static final IdentityHashMap<Entity, Object> WALKING = new IdentityHashMap<>();
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

    public static void tickEnd() {
        if (pausedTicks > 0 && --pausedTicks == 0) {   // try again, measured afresh
            enabled = true;
            waitEma = 0;
        }
        if (renderPending) {   // the last walk's render part, before the next walk reuses its arrays
            long t0 = System.nanoTime();
            finishRender();
            pendingWaitNs += System.nanoTime() - t0;
        }
        ParallelEntities.startAsync();
    }

    /**
     * -Dlab.tickAsync.deferRender (off: lost - the wait moved to the tick's end, 22.8 ms there, DH tour 102 -> 94 fps, p99
     * 39 -> 56 ms): when the frame thread is drawing at a join, only the walk's world part is
     * joined; its render part (poses installed, snapshots let go, the walking set cleared) waits until the tick has run
     * - the frame drawing meanwhile keeps drawing those entities from their snapshots. The join no longer waits for a
     * whole frame (Distant Horizons tour on the host: frames of ~28 ms, TickAsync cost 110 -> 102 fps, p99 29 -> 39 ms).
     */
    static final boolean DEFER = Boolean.getBoolean("lab.tickAsync.deferRender");
    private static boolean renderPending;
    public static long deferredJoins, pendingWaitNs;

    /** runTick's / Minecraft.tick's start: the walk joined (help, then wait) */
    public static void join() {
        if (!ParallelEntities.asyncRunning() || renderPending) return;   // (pending: the world part is done already)
        long t0 = System.nanoTime();
        ParallelEntities.joinAsyncWorld();   // (the frame thread may draw meanwhile: nothing it reads changes here)
        if (DEFER && FrameThread.drawingNow()) {
            renderPending = true;
            deferredJoins++;
        } else finishRender();
        joinWaitNs += System.nanoTime() - t0;
        joins++;
    }

    /** a level change: the walk joined whole, its render part too */
    public static void joinAll() {
        if (!ParallelEntities.asyncRunning()) return;
        if (!renderPending) ParallelEntities.joinAsyncWorld();
        finishRender();
    }

    /**
     * the render part: the frame thread may be drawing from this walk's snapshots and pairs - wait until no frame draws,
     * and no extra frame starts until it is done
     */
    private static void finishRender() {
        renderPending = false;
        long t0 = System.nanoTime();
        FrameThread.holdForMain();
        long w = System.nanoTime() - t0;
        try {
            ParallelEntities.joinAsyncRender();
        } finally {
            FrameThread.releaseForMain();
        }
        if (GIVE_WAY_MS > 0 && FrameThread.active) {
            waitEma += (w / 1e6 - waitEma) * 0.1;
            if (waitEma > GIVE_WAY_MS && enabled) {   // (between ticks: the next tick's split sees it)
                enabled = false;
                pausedTicks = GIVE_WAY_TICKS;
                gaveWay++;
            }
        }
    }

    private static void joinOld() {
        long t0 = System.nanoTime();
        ParallelEntities.joinAsync();
        joinWaitNs += System.nanoTime() - t0;
        joins++;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "tick async: walks %d, entities %d (snapshots %d, unseen %d - waited for in a frame %d), frames holding %d (from snapshots %d), skipped %d, joins %d (%.3f ms a join; render part deferred to the tick's end %d, %.3f ms each there; gave way %d times)",
                walks, entities, snapshots, unseen, unseenWaits, holdFrames, snapFrames, skipped, joins, joins == 0 ? 0.0 : joinWaitNs / 1e6 / joins,
                deferredJoins, deferredJoins == 0 ? 0.0 : pendingWaitNs / 1e6 / deferredJoins, gaveWay);
    }
}
