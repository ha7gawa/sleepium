package dev.lull.optsupport;

import dev.lab.runtime.LabRuntime;
import dev.lab.runtime.OsThreads;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * exp/client-light-async (client): the client's light propagation (LevelRenderer.renderLevel → runLightUpdates, up to a
 * third of the render thread on a relighting scene, and whole batches in single frames) runs on a worker while the
 * rest of the frame renders (on FrameReactor's pool, possibly across frames); it is joined at the reactor's points
 * before the next tick, queued packets or a level change touch the world.
 *
 * <p>Safe to overlap: readers (entity light, section compiles — vanilla's compile workers already read concurrently)
 * use the storage's published copy, which the engine replaces as a whole at the end (copy-on-write data layers);
 * nothing else in the rest of renderLevel writes the engine or the chunks. The engine's section-dirty callbacks
 * (ClientChunkCache.onLightUpdate → LevelRenderer.setSectionDirty) are recorded on the worker and replayed on the
 * render thread in order: handed back through the reactor's completion ring when the job ends, or at a join. Difference from vanilla: those sections are marked dirty at the end of the
 * frame instead of mid-frame, so their rebuild starts one frame later.
 */
public final class ClientLightAsync {
    private ClientLightAsync() {}

    private static volatile boolean running;
    private static final Object LOCK = new Object();
    private static Thread worker;
    private static final LongArrayList DIRTY = new LongArrayList();
    /** the light layer of each DIRTY entry (index into LightLayer.values()) */
    private static final it.unimi.dsi.fastutil.bytes.ByteArrayList DIRTY_LAYER = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
    private static final net.minecraft.world.level.LightLayer[] LAYERS = net.minecraft.world.level.LightLayer.values();
    public static long jobs, waits, replayed, skipped;
    /** set at the light point when a running job is left running: the frame's runLightUpdates is skipped */
    public static boolean skipNext;

    public static boolean running() {
        return running;
    }

    /** FrameReactor participant: ticks, queued packets and level changes need the engine to themselves. */
    private static final FrameReactor.Participant PARTICIPANT = p -> {
        if (p == FrameReactor.Point.BEFORE_TICK || p == FrameReactor.Point.BEFORE_TASKS || p == FrameReactor.Point.LEVEL_CHANGE) join();
    };

    /** In place of the render thread's runLightUpdates. */
    public static void start(LevelLightEngine engine) {
        join();   // never two at once
        FrameReactor.register(PARTICIPANT);
        running = true;
        jobs++;
        FrameReactor.pool().execute(() -> {
            worker = Thread.currentThread();
            try {
                engine.runLightUpdates();
            } finally {
                synchronized (LOCK) {
                    running = false;
                    LOCK.notifyAll();
                }
                FrameReactor.complete(ClientLightAsync::replay);   // the marks, on the render thread at its next point
            }
        });
    }

    /**
     * Sections whose light the running job changes, from before the job publishes the new light (the head of
     * swapSectionMap) until their marks are replayed on the render thread: what is drawn from a record of such a section
     * (BlockMeshMemo) may hold the old light - those records count as stale meanwhile. Without it the memo replayed a
     * moving block's old light for up to a frame (verify mode: 712 of 1.56M hits, all UV2).
     */
    private static final java.util.Set<Long> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean pendingAny;

    /** the head of swapSectionMap on the light worker: the sections it is about to publish new light for */
    public static void pending(it.unimi.dsi.fastutil.longs.LongSet sections) {
        if (sections.isEmpty()) return;
        var it = sections.iterator();
        while (it.hasNext()) PENDING.add(it.nextLong());
        pendingAny = true;
    }

    /** a section whose new light is published (or about to be) but not yet marked */
    public static boolean lightPending(long section) {
        return pendingAny && PENDING.contains(section);
    }

    /** True on the light worker while it runs: the engine's callbacks are recorded, not run. */
    public static boolean onWorker() {
        return running && Thread.currentThread() == worker;
    }

    /** the light engine's onLightUpdate callback on the worker (ClientLightCallbackMixin) */
    public static void record(net.minecraft.world.level.LightLayer layer, SectionPos pos) {
        synchronized (DIRTY) {
            DIRTY.add(pos.asLong());
            DIRTY_LAYER.add((byte) layer.ordinal());
        }
    }

    /** Before anything else may touch the engine or the world: wait, then replay the callbacks. */
    public static void join() {
        if (running) {
            waits++;
            synchronized (LOCK) {
                while (running) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        replay();
    }

    private static void replay() {
        long[] marks;
        byte[] layers;
        synchronized (DIRTY) {
            if (DIRTY.isEmpty()) {
                if (!running) clearPending();
                return;
            }
            marks = DIRTY.toLongArray();
            layers = DIRTY_LAYER.toByteArray();
            DIRTY.clear();
            DIRTY_LAYER.clear();
        }
        // the callback as vanilla makes it, now on the render thread: ClientChunkCache.onLightUpdate with its hooks
        // (ShaderLightChunkCacheMixin, other mods'), the re-mesh inside it
        var level = Minecraft.getInstance().level;
        var lr = Minecraft.getInstance().levelRenderer;
        for (int i = 0; i < marks.length; i++) {
            long p = marks[i];
            if (level != null) level.getChunkSource().onLightUpdate(LAYERS[layers[i]], SectionPos.of(p));
            else if (LightVolumes.ON) LightVolumes.lightChanged(SectionPos.x(p), SectionPos.y(p), SectionPos.z(p));
            else FrameThread.sectionDirty(lr, SectionPos.x(p), SectionPos.y(p), SectionPos.z(p));
        }
        replayed += marks.length;
        if (pendingAny) {   // the replayed marks make their sections' records stale from here on: no longer pending
            for (long p : marks) PENDING.remove(p);
            if (PENDING.isEmpty()) pendingAny = false;
        }
    }

    /** nothing running and nothing left to replay: nothing can be pending */
    private static void clearPending() {
        if (!pendingAny) return;
        pendingAny = false;
        PENDING.clear();
    }

    public static String report() {
        return "client light async: jobs " + jobs + ", frames it kept running " + skipped + ", joins that waited " + waits + ", section marks replayed " + replayed;
    }
}
