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
        return enabled && UNSEEN && !(e instanceof net.minecraft.world.entity.player.Player) && !ParallelRender.drawnLately(e);
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

    /** render thread: the entity is ticking on the pool - frames draw it only from its held pair */
    public static boolean holding(Entity e) {
        return !WALKING.isEmpty() && WALKING.containsKey(e);
    }

    /** Minecraft.tick returned: the left-out entities go to the pool */
    public static void tickEnd() {
        ParallelEntities.startAsync();
    }

    /** runTick's / Minecraft.tick's start: the walk joined (help, then wait) */
    public static void join() {
        if (!ParallelEntities.asyncRunning()) return;
        long t0 = System.nanoTime();
        ParallelEntities.joinAsync();
        joinWaitNs += System.nanoTime() - t0;
        joins++;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "tick async: walks %d, entities %d (snapshots %d, unseen %d), frames holding %d (from snapshots %d), skipped %d, joins %d (%.3f ms a join)",
                walks, entities, snapshots, unseen, holdFrames, snapFrames, skipped, joins, joins == 0 ? 0.0 : joinWaitNs / 1e6 / joins);
    }
}
