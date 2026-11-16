package dev.lull.optsupport;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;

/**
 * -Dlab.opt.FrameThreadMixin=true (1.3.0 research, plan D, full decoupling - stage A1b): the GL context lives on a
 * thread of its own ("Sleepium frame") for good; the main thread keeps the game loop (tasks, the tick, input, the
 * window). The render half of Minecraft.runTick - clear, bind, RenderFramePre, GameRenderer.render, RenderFramePost,
 * unbind, blit, Window.updateDisplay (replayQueue, swap) - is recorded on the main thread (FrameThreadMixin's
 * redirects) and run on the frame thread as one job at updateDisplay; glfwPollEvents stays on the main thread (before
 * and after the job; the frame thread's flipFrame skips its polls). GL work the main thread still does (the texture
 * tick, mods' code, map textures) borrows the context at a safe point - the frame thread idle - on its first
 * RenderSystem assert, and gives it back when the next frame is handed over; borrows are counted (observation).
 * Stage A1b keeps vanilla's order (the main thread waits for the frame); A2 lets the next tick overlap it.
 */
public final class FrameThread {
    private FrameThread() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final boolean ON = Boolean.getBoolean("lab.opt.FrameThreadMixin");
    private static Thread thread, mainThread;
    private static org.lwjgl.opengl.GLCapabilities caps;
    private static long window;
    private static java.lang.reflect.Field renderThreadField;
    /** the frame thread runs (from the first frame in a world on: the context is its own) */
    public static volatile boolean active;
    /** the main thread holds the context (a borrow), until the next frame is handed over */
    private static volatile boolean mainHolds;
    /** the frame thread: 1 holds the context, 0 released */
    private static volatile boolean frameHolds;
    private static volatile boolean borrowAsked, drawing;
    private static volatile Throwable failure;

    // the recorded render half: op codes and their arguments
    public static final int CLEAR = 1, BIND_WRITE = 2, SETUP_NO_FOG = 3, ENABLE_CULL = 4, FRAME_PRE = 5, RENDER = 6, FRAME_POST = 7,
            UNBIND_WRITE = 8, BLIT = 9, UPDATE_DISPLAY = 10;

    /** a recorded frame: its op list, the renderer calls and section marks handed over with it, the world's lists held for it */
    private static final class Slot {
        final int[] ops = new int[32], argI = new int[32], argJ = new int[32];
        final boolean[] argZ = new boolean[32];
        @SuppressWarnings("rawtypes")
        final com.llamalad7.mixinextras.injector.wrapoperation.Operation[] operations = new com.llamalad7.mixinextras.injector.wrapoperation.Operation[32];
        final Object[][] args = new Object[32][];
        int nOps;
        final java.util.ArrayList<com.llamalad7.mixinextras.injector.wrapoperation.Operation<?>> calls = new java.util.ArrayList<>();
        final java.util.ArrayList<Object[]> callArgs = new java.util.ArrayList<>();
        int[] dirty = new int[4 * 64];
        int nDirty;
        final java.util.ArrayList<net.minecraft.world.entity.Entity> held = new java.util.ArrayList<>();
        final java.util.LinkedHashMap<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<net.minecraft.client.particle.Particle>> heldParticles = new java.util.LinkedHashMap<>();
        boolean heldValid;
        /** PUBLISH: the published entity list and particle queues this frame draws (shared between frames) */
        EntList ents;
        PartMap parts;
        /** the side context's fence at this frame's hand-over */
        long fence;
    }

    /**
     * -Dlab.frameThread.ring=N (1: off): N recorded frames in a ring. The main thread records into a free slot and goes on
     * (its next tasks and tick); it waits - by event, not a spin - only when every slot is taken: the one the frame thread
     * draws (or drew last: the extra frames redraw it), and the ones handed over and not yet drawn. The frame thread takes
     * them in order. With 1 the main thread waits for the last frame before it records the next (A2 overlap as before).
     * A frame drawn later than recorded reads the world as it is then (the partial tick from the clock, ptFromTickEnd);
     * the input is at most N-2 frames older than it was.
     */
    static final int RING = Math.max(1, Math.min(8, Integer.getInteger("lab.frameThread.ring", 1)));
    private static final Slot[] SLOTS = new Slot[RING];
    static {
        for (int i = 0; i < RING; i++) SLOTS[i] = new Slot();
    }
    /** the slots of the frames handed over, by job number (job k: JOBQ[k % RING]) */
    private static final int[] JOBQ = new int[RING];
    /** frames handed over (the main thread), done (the frame thread): no job waiting or drawing when equal */
    private static volatile long handed, done;
    /** the frame thread: jobs taken so far */
    private static long taken;
    /** the slot the frame thread draws or drew last (-1: none yet); the main thread never records into it */
    private static volatile int drawIdx = -1;
    /** the slot the frame thread draws now (its held lists) */
    private static volatile Slot cur;
    /** the main thread: the slot it records into */
    private static Slot rec = SLOTS[0];
    private static int recIdx;
    /** jobs below this were handed over before a level change: their calls are made, their frames not drawn */
    private static volatile long dropBelow;
    public static long ringWaits, ringWaitNs, ringDropped, ringQueuedMax;

    /** TickAsync generations: published by the main thread after a join's swap (TickAsync.GEN) */
    private static volatile long gen;
    /** the generation the frame thread's current job or extra frame began in; MAX_VALUE: none drawing */
    private static volatile long drawGen = Long.MAX_VALUE;

    /** the main thread, after it swapped what frames read: a new generation (frames begun from now on see the new state) */
    public static long publish() {
        return ++gen;
    }

    /** the main thread: no frame begun before generation g still draws (waits by event); true if it waited */
    public static boolean awaitDrawnSince(long g) {
        if (!active || drawGen >= g) return false;
        waitPumping(() -> drawGen >= g);
        return true;
    }

    /** no frame handed over and not yet done */
    private static boolean idle() {
        return done == handed;
    }
    private static Minecraft mc;
    public static long frames, borrows, waitNs, borrowWaitNs, failures;
    /** frame jobs over 10 ms: count, total, by part (frameBegin + queue, the main thread's calls, ops), the calls' count, the longest */
    public static long slowJobs, slowJobNs, slowReplayNs, slowCallsNs, slowOpsNs, slowCallsN, slowJobMax;
    private static final java.util.Map<String, long[]> BORROW_SITES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void setRenderThread(Thread t) {
        try {
            if (renderThreadField == null) {
                renderThreadField = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("renderThread");
                renderThreadField.setAccessible(true);
            }
            renderThreadField.set(null, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the main thread records this frame's render half (not on the frame thread, in a world) */
    /** -Dlab.frameThread.inactive=true: the hooks in place, the frame thread never started (a baseline with the same hooks) */
    private static final boolean INACTIVE = Boolean.getBoolean("lab.frameThread.inactive");

    public static boolean recording() {
        if (!ON || INACTIVE) return false;
        if (!active) {
            Minecraft m = Minecraft.getInstance();
            if (m.level == null || m.player == null || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return false;
            start(m);
        }
        return Thread.currentThread() == mainThread;
    }

    private static void start(Minecraft m) {
        mc = m;
        OwnerThread.client = Thread.currentThread();   // (the client's ticks stay here; EntitySleep's sides, the memos' owner checks)
        mainThread = Thread.currentThread();
        caps = org.lwjgl.opengl.GL.getCapabilities();
        window = m.getWindow().getWindow();
        thread = new Thread(FrameThread::loop, "Sleepium frame");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        // the context goes to the frame thread (it takes it at its first job); the main thread keeps a side context of
        // its own, shared with it: other mods' raw GL calls from the main thread (Sodium's fence in runTick) land there
        // instead of aborting the JVM with no context current (their textures and buffers are shared objects)
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
        org.lwjgl.opengl.GL.setCapabilities(null);
        sideOn();
        setRenderThread(thread);
        active = true;
        thread.start();
        LOG.info("[sleep/opt] frame thread: the GL context and the frame's drawing on a thread of their own (stage A1b)");
    }

    /**
     * -Dlab.frameThread.overlap=true (stage A2 test, NOT exact: nothing holds the world yet): present() does not wait -
     * the main thread's next tasks, packets and tick run while the frame thread draws; the next frame's first recorded
     * op waits for it. Flippable (ClientAuto).
     */
    public static volatile boolean OVERLAP = Boolean.getBoolean("lab.frameThread.overlap");
    private static boolean pending;

    /** the light point (ClientLightAsyncMixin's, set when that mixin's class loads) - null: vanilla's two calls */
    public static java.util.function.Consumer<net.minecraft.client.multiplayer.ClientLevel> lightPoint;

    /** renderLevel's light point is skipped on the frame thread under OVERLAP: the main thread did it before the hand-over */
    public static boolean lightOnMain() {
        Slot c = cur;
        return c != null && c.heldValid && drawingHere();
    }

    /** the frame thread under OVERLAP: the particle queues held for this frame, else null (the live ones) */
    public static java.util.Map<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<net.minecraft.client.particle.Particle>> heldParticles() {
        Slot c = cur;
        return c != null && c.heldValid && drawingHere() ? (PUBLISH ? c.parts.map : c.heldParticles) : null;
    }

    /** the frame thread under OVERLAP: the entity list held for this frame, else null (the live one) */
    public static Iterable<net.minecraft.world.entity.Entity> heldEntities() {
        Slot c = cur;
        return c != null && c.heldValid && drawingHere() ? (PUBLISH ? c.ents.list : c.held) : null;
    }

    // ------------------------------------------------------------------ published lists (notes/75, stage 1)

    /**
     * -Dlab.frameThread.publish (in lab.decoupled): the lists frames draw are published when they change instead of
     * copied by the main thread at every hand-over - the entity list when an entity begins or ends being tracked
     * (FrameThreadTrackingMixin), the particle queues at the end of each client tick (vanilla moves new particles into
     * them only in its tick). The same content as the copies; frames share a published list until the next one. Lists
     * come from a small pool (ring + 2) and are reused once no frame in flight holds them.
     */
    public static final boolean PUBLISH = Boolean.getBoolean("lab.frameThread.publish");

    // ------------------------------------------------------------------ self-clocked frames (notes/75, stage 3)

    /**
     * -Dlab.frameThread.selfClock (with the ring): with no screen open, the frame thread draws on its own clock (the
     * frame rate limit, vsync, or as fast as it can) from the last recorded frame and the latest published lists - every
     * frame at the partial tick from the tick's end, its camera turned by the mouse not yet applied (latchTurn) - and
     * the main thread no longer runs once a frame: it hands a new recorded frame over only when it carries something
     * (renderer calls, section marks, a tick ran, new published lists, or 50 ms passed) and otherwise waits for events
     * until the next tick is due - woken earlier by a task from another thread (FrameThreadTaskWakeMixin), the mouse
     * (at most every 2 ms) or a screen. Keys and clicks are handled in the tick, as in vanilla.
     */
    public static final boolean SELF = Boolean.getBoolean("lab.frameThread.selfClock") && RING > 1;
    /** the main thread's view: frames may run on their own now (no screen, a level, not paused) */
    private static volatile boolean selfOk;
    private static volatile boolean taskPending, mainIdle;
    private static long lastHandNs, lastTickStartNs, runTickAt;
    private static boolean tickedSinceHand;
    private static EntList handedEnts;
    private static PartMap handedParts;
    public static long prepAhead;
    public static long selfSkips, selfHands, idleWaits, idleWakesTask, idleWakesMouse, idleWakesDue, idleWakesScreen, selfFrames;

    private static Boolean selfAllowed;

    /**
     * self-clocked frames only while no other mod's mixin changes Minecraft.runTick (its per-frame work would run at the
     * main thread's pace instead of the frames'): learnt once from the mixins (MixinTouch), the yielding logged
     */
    private static boolean selfAllowed() {
        Boolean a = selfAllowed;
        if (a == null) {
            boolean touched = MixinTouch.touched("net/minecraft/client/Minecraft", "runTick");
            a = !touched;
            selfAllowed = a;
            if (touched) LOG.info("[sleep/opt] frame thread: self-clocked frames off - Minecraft.runTick is changed by {}", MixinTouch.who("net/minecraft/client/Minecraft", "runTick"));
        }
        return a;
    }

    /** runTick's frame rate limit is not the main thread's to keep (it waits for events instead) */
    public static boolean selfClocking() {
        return SELF && active && selfOk && Thread.currentThread() == mainThread;
    }

    /** another thread handed Minecraft a task (FrameThreadTaskWakeMixin): the main thread woken from its wait (once) */
    public static void taskPosted() {
        if (!SELF || !active || Thread.currentThread() == mainThread || taskPending) return;
        taskPending = true;
        if (mainIdle) org.lwjgl.glfw.GLFW.glfwPostEmptyEvent();
    }

    /**
     * self-clocked: the main thread's renderer calls and section marks, made by the frame thread at the start of its
     * next frame (a handed one or one of its own) - they no longer need a recorded frame handed over
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> SELF_CALLS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** the frame thread at a frame's start: the queued calls, in order */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void drainSelfCalls() {
        Object[] c;
        while ((c = SELF_CALLS.poll()) != null) ((com.llamalad7.mixinextras.injector.wrapoperation.Operation) c[0]).call((Object[]) c[1]);
    }

    /** the main thread (self-clocked): this frame's calls and marks into the queue instead of the slot */
    private static void queueSelfCalls(Slot r) {
        for (int i = 0; i < r.calls.size(); i++) SELF_CALLS.add(new Object[] {r.calls.get(i), r.callArgs.get(i)});
        r.calls.clear();
        r.callArgs.clear();
        if (r.nDirty > 0) {
            final int[] d = java.util.Arrays.copyOf(r.dirty, r.nDirty * 4);
            final int n = r.nDirty;
            r.nDirty = 0;
            Runnable marks = () -> {
                net.minecraft.client.renderer.LevelRenderer lr = mc.levelRenderer;
                for (int i = 0; i < n; i++)
                    ((dev.lull.opt.FrameThreadDirtyInvoker) lr).sleep$setSectionDirty(d[i * 4], d[i * 4 + 1], d[i * 4 + 2], d[i * 4 + 3] != 0);
            };
            SELF_CALLS.add(new Object[] {RUN_OP, new Object[] {marks}});
        }
    }

    private static volatile boolean walkReady;
    public static long idleWakesWalk;

    /** TickAsync's walk done (a worker): the main thread woken to join it - the frames draw its new poses at once */
    public static void walkDone() {
        if (!SELF || !active) return;
        walkReady = true;
        if (mainIdle) org.lwjgl.glfw.GLFW.glfwPostEmptyEvent();
    }

    private static volatile boolean askPosted;

    /**
     * a worker asked the main thread a block shape (ParticleWorkers: TickAsync's walk, parallel particles) - only the
     * main thread answers: woken from its wait for events or for the frame thread (once until it answers)
     */
    public static void workerAsked() {
        if (!active || askPosted || !(mainIdle || mainWaiting)) return;
        askPosted = true;
        org.lwjgl.glfw.GLFW.glfwPostEmptyEvent();
    }

    /** the main thread, waiting: the workers' shape questions answered */
    private static void serveWorkers() {
        if (ParticleWorkers.pending() && Thread.currentThread() == ParticleWorkers.renderThread) {
            askPosted = false;
            ParticleWorkers.serve();
        }
    }

    /** the main thread at a hand-over (self-clocked): whether this recorded frame must go to the frame thread */
    private static boolean needHand(Slot r) {
        int why = holdToFrame ? 0 : !r.calls.isEmpty() ? 1 : r.nDirty > 0 ? 2 : tickedSinceHand ? 3 : r.ents != handedEnts || r.parts != handedParts ? 4
                : System.nanoTime() - lastHandNs > 50_000_000L ? 5 : -1;
        if (why >= 0) HAND_WHY[why]++;
        return why >= 0;
    }

    /** the hand-overs by their first reason: level change, renderer calls, section marks, a tick, new lists, 50 ms */
    private static final long[] HAND_WHY = new long[6];
    /** when the frame thread last began a frame; when the next one is due at the limit (self-clocked pacing, drift-free) */
    private static long frameStartAt, nextDue;

    /** the frame thread, a frame beginning now (self-clocked): the next one's due time moved on by the interval */
    private static void paced(long now, long iv) {
        frameStartAt = now;
        nextDue = iv <= 0 || now - nextDue > iv ? now + iv : nextDue + iv;
    }

    /**
     * -Dlab.frameThread.hiResPace: the frame thread's waits for a frame's due time as one Thread.sleep (a high-resolution
     * timer on Windows) of the time left minus 0.5 ms, a frame less than PACE_EARLY early drawn at once - not
     * LockSupport.parkNanos: standalone, parkNanos(0.5 / 2 / 6 ms) took 15.7 ms on the N100 and the host (the system
     * timer's step), Thread.sleep 1.1 / 2.1 / 6.1 ms. Sleeps of 1-ms slices lost (each at least 1.1 ms: the N100's
     * stall probe 231 -> 519 long frames, 11-16).
     */
    static final boolean HI_RES_PACE = Boolean.getBoolean("lab.frameThread.hiResPace");
    /** a due time nearer than this: the frame is drawn now (no sleep is that short) */
    static final long PACE_EARLY = HI_RES_PACE ? 1_500_000L : 0;

    private static void paceSleep(long nanos) {
        if (!HI_RES_PACE) {
            LockSupport.parkNanos(nanos);
            return;
        }
        long n = Math.max(1_000_000L, nanos - 500_000L);
        try {
            Thread.sleep(n / 1_000_000L, (int) (n % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long capInterval() {
        int cap = mc != null ? mc.getWindow().getFramerateLimit() : 260;
        long iv = cap > 0 && cap < 260 ? 1_000_000_000L / cap : 0;
        // the tick first: while the client tick or TickAsync's walk runs, frames of its own no more often than this (with
        // no limit the frame thread and the render pool took the N100's four cores - the tick 1.5 -> 2.1 ms, 11-16)
        if (TICK_YIELD_NS > 0 && (inClientTick || ParallelEntities.asyncRunning() && !ParallelEntities.asyncFinished())) iv = Math.max(iv, TICK_YIELD_NS);
        return iv;
    }

    /** -Dlab.frameThread.tickYieldMs (4; 0 = off): self-clocked frames' interval at least this while the tick runs */
    private static final long TICK_YIELD_NS = (long) (Double.parseDouble(System.getProperty("lab.frameThread.tickYieldMs", "4")) * 1e6);

    /** the main thread, nothing to hand over: asleep in the window's event wait until the next tick is due (or woken) */
    private static void mainIdleWait() {
        idleWaits++;
        float ms = 50f;
        long due = lastTickStartNs + (long) (ms * 1e6);
        dev.lull.opt.FrameThreadMouseAccessor m = (dev.lull.opt.FrameThreadMouseAccessor) mc.mouseHandler;
        mainIdle = true;
        try {
            while (true) {
                serveWorkers();
                if (taskPending && System.nanoTime() - runTickAt >= 2_000_000L) {   // (packets coalesced: at most every 2 ms)
                    idleWakesTask++;
                    break;
                }
                if (walkReady) {
                    idleWakesWalk++;
                    break;
                }
                if (mc.screen != null || !selfOk) {
                    idleWakesScreen++;
                    break;
                }
                long now = System.nanoTime();
                if (now >= due) {
                    idleWakesDue++;
                    break;
                }
                if ((m.sleep$accumulatedDX() != 0 || m.sleep$accumulatedDY() != 0) && now - runTickAt >= 2_000_000L) {
                    idleWakesMouse++;
                    break;
                }
                boolean was = inGlfw;
                inGlfw = true;
                try {
                    long slice = taskPending ? Math.max(100_000L, runTickAt + 2_000_000L - now) : 2_000_000L;
                    org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout(Math.min(slice, due - now) / 1e9);
                } finally {
                    inGlfw = was;
                }
                polled();
            }
        } finally {
            mainIdle = false;
        }
    }

    static final class EntList {
        final java.util.ArrayList<net.minecraft.world.entity.Entity> list = new java.util.ArrayList<>();
    }

    static final class PartMap {
        final java.util.LinkedHashMap<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<net.minecraft.client.particle.Particle>> map = new java.util.LinkedHashMap<>();
    }

    private static final EntList[] ENT_POOL = new EntList[RING + 2];
    private static final PartMap[] PART_POOL = new PartMap[RING + 2];
    static {
        for (int i = 0; i < RING + 2; i++) {
            ENT_POOL[i] = new EntList();
            PART_POOL[i] = new PartMap();
        }
    }
    private static EntList pubEnts;
    private static PartMap pubParts;
    private static Object pubLevel;
    private static volatile boolean entsDirty = true;
    public static long entPublishes, partPublishes;

    /** the main thread: the level's tracked entities changed (FrameThreadTrackingMixin) */
    public static void entitiesChanged() {
        entsDirty = true;
    }

    /** the main thread: o is held by a frame drawing, drawn last (extra frames) or handed over and waiting */
    private static boolean heldByFrames(Object o) {
        long d = done, h = handed;
        int di = drawIdx;
        if (di >= 0 && (SLOTS[di].ents == o || SLOTS[di].parts == o)) return true;
        for (long k = d; k < h; k++) {
            Slot s = SLOTS[JOBQ[(int) (k % RING)]];
            if (s.ents == o || s.parts == o) return true;
        }
        return false;
    }

    private static EntList freeEnts() {
        for (EntList e : ENT_POOL) if (e != pubEnts && !heldByFrames(e)) return e;
        throw new IllegalStateException("frame thread: no free entity list");   // (ring + 2: at most ring held + the published)
    }

    private static PartMap freeParts() {
        for (PartMap p : PART_POOL) if (p != pubParts && !heldByFrames(p)) return p;
        throw new IllegalStateException("frame thread: no free particle map");
    }

    /** the main thread: the particle queues as the tick left them, published */
    private static void publishParticles() {
        PartMap p = freeParts();
        p.map.clear();
        for (var en : ((dev.lull.opt.ParticleEngineAccessor) mc.particleEngine).sleep$particles().entrySet())
            p.map.put(en.getKey(), new java.util.ArrayDeque<>(en.getValue()));
        pubParts = p;
        partPublishes++;
    }

    /** the main thread at a hand-over: the published lists brought up to date (entities if changed) and given to r */
    private static void publishInto(Slot r) {
        if (entsDirty || pubLevel != mc.level || pubEnts == null) {
            entsDirty = false;
            EntList e = freeEnts();
            e.list.clear();
            if (mc.level != null) for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) e.list.add(en);
            pubEnts = e;
            entPublishes++;
            if (pubLevel != mc.level || pubParts == null) publishParticles();   // (a new level: its particle engine was cleared)
            pubLevel = mc.level;
        }
        r.ents = pubEnts;
        r.parts = pubParts;
        r.heldValid = mc.level != null;
    }
    public static long overlapFrames, overlapWaitNs;

    /** sections the main thread marked dirty while a frame drew: x, y, z, flags (bit 0: re-render on the main thread) */
    private static int[] DIRTY = new int[4 * 256];
    private static int nDirty;
    public static long deferredDirty;

    /** LevelRenderer.setSectionDirty on the main thread while a frame draws (OVERLAP): queued (true), else false */
    public static boolean deferDirty(int x, int y, int z, boolean mainThread) {
        // (queued whenever a frame or an extra frame may be drawing - not only a pending one: an extra frame reads the same
        // sets; replayed where neither draws - begin, holdForMain; the frame thread audit, 11-16)
        // (ring: queued always - a frame may draw while the main thread records the next; they go with the next slot)
        if (!active || Thread.currentThread() != mainThread() || RING == 1 && mainRecording || mainRecordingReplay || mainHolding) return false;
        if (nDirty * 4 + 4 > DIRTY.length) DIRTY = java.util.Arrays.copyOf(DIRTY, DIRTY.length * 2);
        int o = nDirty++ * 4;
        DIRTY[o] = x;
        DIRTY[o + 1] = y;
        DIRTY[o + 2] = z;
        DIRTY[o + 3] = mainThread ? 1 : 0;
        deferredDirty++;
        return true;
    }

    /** calls into the renderer the main thread made while a frame drew: their Operations and arguments, made after it */
    private static final java.util.ArrayList<com.llamalad7.mixinextras.injector.wrapoperation.Operation<?>> CALLS = new java.util.ArrayList<>();
    private static final java.util.ArrayList<Object[]> CALL_ARGS = new java.util.ArrayList<>();
    public static long deferredCalls;

    /**
     * FrameThreadCallsMixin: a call of the main thread into the level renderer, queued (true) - it is made on the frame
     * thread at the start of the next frame, before that frame draws (vanilla made it before the frame too): the
     * renderer's side - vanilla's, Sodium's (its RenderAsserts want the render thread) - only ever runs there
     */
    /**
     * other mods' render-thread tasks (Distant Horizons: its GL work through Minecraft.execute, DhRenderTasksMixin), from
     * any thread: run at the frame thread's next job start
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> RENDER_TASKS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public static long renderTasks, renderTaskFailures, nestedDropped;

    /** particles added on the frame thread, for the main thread's next particle tick (FrameThreadParticleAddMixin) */
    public static final java.util.concurrent.ConcurrentLinkedQueue<net.minecraft.client.particle.Particle> FRAME_PARTICLES = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static boolean takeRenderTask(Runnable r) {
        if (!active) return false;
        RENDER_TASKS.add(r);
        return true;
    }

    private static void runRenderTasks() {
        Runnable t;
        while ((t = RENDER_TASKS.poll()) != null) {
            renderTasks++;
            try {
                t.run();
            } catch (Throwable e) {
                if (renderTaskFailures++ < 5) {
                    System.out.println("[sleep/opt] frame thread: a mod's render-thread task failed: " + e);
                    e.printStackTrace(System.out);
                }
            }
        }
    }

    private static final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Object> RUN_OP = a -> {
        ((Runnable) a[0]).run();
        return null;
    };

    /** the main thread: r (GL work) at the frame thread's next job start; false: not deferred, run it here */
    public static boolean deferRun(Runnable r) {
        return deferCall(RUN_OP, r);
    }

    public static boolean deferCall(com.llamalad7.mixinextras.injector.wrapoperation.Operation<?> op, Object... args) {
        if (!active || Thread.currentThread() != mainThread || mainHolds) return false;
        for (int i = 0; i < args.length; i++)   // (a caller may reuse its mutable position after the call)
            if (args[i] instanceof net.minecraft.core.BlockPos bp) args[i] = bp.immutable();
        CALLS.add(op);
        CALL_ARGS.add(args);
        deferredCalls++;
        return true;
    }

    private static final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> SECTION_DIRTY = a -> {
        ((net.minecraft.client.renderer.LevelRenderer) a[0]).setSectionDirty((Integer) a[1], (Integer) a[2], (Integer) a[3]);
        return null;
    };

    /** the engine's own section marks (light volumes, async light, voxel stream): through the frame thread's queue when it runs */
    public static void sectionDirty(net.minecraft.client.renderer.LevelRenderer lr, int x, int y, int z) {
        if (!deferCall(SECTION_DIRTY, lr, x, y, z)) lr.setSectionDirty(x, y, z);
    }

    /** this is the main thread while the frame thread runs (a plain identity check: no isSameThread on hot paths) */
    public static boolean onMainThread() {
        return active && Thread.currentThread() == mainThread;
    }

    private static Thread mainThread() {
        return mainThread;
    }

    /** the main thread before it records a new frame: the last one (still drawing under OVERLAP) done */
    private static void awaitLast() {
        if (!pending) return;
        long t0 = System.nanoTime();
        waitPumping(FrameThread::idle);
        long w = System.nanoTime() - doneAt;
        if (w >= 0 && w < 100_000_000L) {
            mainWakeNs += w;
            mainWakes++;
        }
        pending = false;
        overlapWaitNs += System.nanoTime() - t0;
        checkFailure();
    }

    /** the main thread: a failure of the frame thread's last job thrown here */
    private static void checkFailure() {
        Throwable f = failure;
        if (f != null) {
            failure = null;
            failures++;
            if (f instanceof RuntimeException r) throw r;
            if (f instanceof Error e) throw e;
            throw new RuntimeException(f);
        }
    }

    /** the next op begins a new frame (set by present): the last one waited for, the list started over */
    private static boolean fresh = true;

    /** frames begun so far: present() sees whether its own poll ran a nested runTick (recordSeq moved) */
    private static long recordSeq;

    /**
     * -Dlab.frameThread.noWait (full decoupling, an experiment): the main thread never waits for the frame thread - when
     * the last frame is still drawing, this runTick's frame is skipped (nothing recorded, the op list left as it is: the
     * frame thread draws extra frames from it meanwhile) and the main thread goes on to its input, tasks and ticks.
     */
    public static final boolean NO_WAIT = Boolean.getBoolean("lab.frameThread.noWait");
    /** this runTick's frame is skipped (NO_WAIT) */
    private static boolean skipping;
    public static long skippedFrames;
    /** the main thread's window polls: the gaps between them (input latency's floor), 0.1-ms buckets up to 100 ms */
    private static final long[] POLL_GAPS = new long[1001];
    private static long lastPollNs;

    /** the main thread, after each glfwPollEvents of ours */
    private static void polled() {
        long now = System.nanoTime();
        if (lastPollNs != 0 && dev.lull.ClientMeter.measuring()) POLL_GAPS[(int) Math.min(1000, (now - lastPollNs) / 100_000L)]++;
        lastPollNs = now;
    }

    private static String pollGaps() {
        long n = 0;
        for (long c : POLL_GAPS) n += c;
        if (n == 0) return "";
        long[] want = {n / 2, n * 99 / 100, n * 999 / 1000};
        double[] at = new double[3];
        long acc = 0;
        int k = 0, max = 0;
        for (int i = 0; i < POLL_GAPS.length; i++) {
            if (POLL_GAPS[i] > 0) max = i;
            acc += POLL_GAPS[i];
            while (k < 3 && acc > want[k]) at[k++] = i / 10.0;
        }
        return String.format(java.util.Locale.ROOT, "; window polls' gaps (measured window): p50 %.1f, p99 %.1f, p99.9 %.1f, max %s ms", at[0], at[1], at[2], max >= 1000 ? ">100" : String.valueOf(max / 10.0));
    }

    private static void begin() {
        if (NO_WAIT && pending && !idle()) {
            skipping = true;
            fresh = false;
            skippedFrames++;
            return;
        }
        recordSeq++;
        if (RING > 1) {   // a free slot (waited for by event only when the ring is full); the frame thread may draw meanwhile
            checkFailure();
            mainRecording = true;
            int s = freeSlot();
            if (s < 0) {
                long t0 = System.nanoTime();
                waitPumping(() -> freeSlot() >= 0);
                s = freeSlot();
                ringWaits++;
                ringWaitNs += System.nanoTime() - t0;
                checkFailure();
            }
            recIdx = s;
            rec = SLOTS[s];
            rec.nOps = 0;
            fresh = false;
            return;
        }
        awaitLast();
        mainRecording = true;   // (before the check: an extra frame starting now sees it and backs off)
        if (extraRunning) waitPumping(() -> !extraRunning);
        replayDirty();
        rec.nOps = 0;
        fresh = false;
    }

    /** the main thread (ring): a slot neither drawn (or drawn last) nor handed over and waiting; -1: none */
    private static int freeSlot() {
        long d = done, h = handed;
        int di = drawIdx;
        for (int s = 0; s < RING; s++) {
            if (s == di) continue;
            boolean busy = false;
            for (long k = d; k < h && !busy; k++) busy = JOBQ[(int) (k % RING)] == s;
            if (!busy) return s;
        }
        return -1;
    }

    public static void record(com.llamalad7.mixinextras.injector.wrapoperation.Operation<?> operation, Object... args) {
        if (fresh) begin();
        if (skipping) return;
        Slot r = rec;
        r.operations[r.nOps] = operation;
        r.args[r.nOps] = args;
        r.ops[r.nOps++] = 0;
    }

    public static void op(int code) {
        if (fresh) begin();
        if (skipping) return;
        Slot r = rec;
        r.ops[r.nOps++] = code;
    }

    public static void op(int code, int i, int j, boolean z) {
        if (fresh) begin();
        if (skipping) return;
        Slot r = rec;
        r.argI[r.nOps] = i;
        r.argJ[r.nOps] = j;
        r.argZ[r.nOps] = z;
        r.ops[r.nOps++] = code;
    }

    /** this is the frame thread, running a frame (identity checks: the loop's own thread, owners) */
    public static boolean drawingHere() {
        return drawing && Thread.currentThread() == thread;
    }

    public static boolean onFrameThread() {
        return active && Thread.currentThread() == thread;
    }

    public static Thread ownerView() {
        Thread t = Thread.currentThread();
        return drawing && t == thread ? mainThread : t;
    }

    // ------------------------------------------------------------------ the main thread

    /** Window.updateDisplay in runTick (recording): poll, hand the recorded frame over, wait (A1b), poll */
    public static long tPoll, tFence, tHeld, tLight, tSideWait, tReplay, tCalls, tOps;

    public static void present() {
        if (skipping) {   // NO_WAIT: no frame this time - the window polled, a short wait for the frame thread (bounded)
            skipping = false;
            fresh = true;
            pollWindow();
            polled();
            long until = System.nanoTime() + 1_000_000L;
            waitPumping(() -> idle() || System.nanoTime() > until);
            return;
        }
        Slot r = rec;
        long p0 = System.nanoTime();
        long seq = recordSeq;
        // this frame's op list is complete: a runTick nested in the poll below begins a list of its own (begin: recordSeq
        // moves) - without this its ops were appended to this frame's and handed over with it (the exit crash, 11-16)
        fresh = true;
        pollWindow();
        polled();
        if (recordSeq != seq) {
            // the poll ran a runTick of its own (a click on "Save and Quit": Minecraft.disconnect's forced frames) - it
            // recorded over this frame's ops and handed its frames over; this one, of the world before, is dropped
            // (11-16 hand test: it was drawn after gameMode was gone, an NPE in renderItemInHand)
            nestedDropped++;
            return;
        }
        if (mainHolds) giveBack();
        cpuMark();
        if (LATCH) turnParams();
        long p1 = System.nanoTime();
        tPoll += p1 - p0;
        sideFence(r);
        tFence += System.nanoTime() - p1;
        FrameRandom.install(mc.level);   // (the level's random per thread: render code may draw from it)
        r.calls.addAll(CALLS);
        r.callArgs.addAll(CALL_ARGS);
        CALLS.clear();
        CALL_ARGS.clear();
        if (RING > 1) {   // the section marks queued since the last hand-over go with this frame (made on the frame thread)
            if (r.dirty.length < nDirty * 4) r.dirty = new int[DIRTY.length];
            System.arraycopy(DIRTY, 0, r.dirty, 0, nDirty * 4);
            r.nDirty = nDirty;
            nDirty = 0;
        }
        long t0 = System.nanoTime();
        fresh = true;
        long h0 = System.nanoTime();
        if (OVERLAP) {   // the world's lists the frame walks, as they are now (the main thread changes them meanwhile)
            // the crosshair's target here, on the main thread (FrameThreadPickMixin: not on the frame thread)
            if (mc.level != null && mc.getCameraEntity() != null) mc.gameRenderer.pick(realPt());
            if (PUBLISH) publishInto(r);
            else {
                r.held.clear();
                if (mc.level != null) for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) r.held.add(e);
                r.heldParticles.clear();
                for (var en : ((dev.lull.opt.ParticleEngineAccessor) mc.particleEngine).sleep$particles().entrySet())
                    r.heldParticles.put(en.getKey(), new java.util.ArrayDeque<>(en.getValue()));
                r.heldValid = mc.level != null;
            }
            if (mc.level != null && mc.getConnection() != null && mc.getConnection().getLevel() == mc.level) {   // renderLevel's light point, here (packets and the tick may change the light engine while the frame draws)
                if (lightPoint != null) lightPoint.accept(mc.level);
                else {
                    mc.level.pollLightUpdates();
                    mc.level.getChunkSource().getLightEngine().runLightUpdates();
                }
            }
        } else r.heldValid = false;
        tHeld += System.nanoTime() - h0;
        handAt = System.nanoTime();
        mainRecording = false;
        if (holdToFrame) {   // the level change's hold ends here - only it: a hold around it (a respawn's) goes on
            holdToFrame = false;
            if (holdDepth > 0) holdDepth--;
            if (holdDepth == 0) mainHolding = false;
        }
        if (SELF && OVERLAP) {
            selfOk = mc.screen == null && mc.level != null && !mc.isPaused() && selfAllowed();
            if (selfOk && !pending) selfOk = false;   // (the first frame goes over the usual way)
            if (selfOk) queueSelfCalls(r);
            if (selfOk && !needHand(r)) {   // nothing new: the frame thread keeps drawing the last one; wait for events
                selfSkips++;
                mainRecording = false;
                pollWindow();
                polled();
                mainIdleWait();
                return;
            }
            selfHands++;
            lastHandNs = System.nanoTime();
            tickedSinceHand = false;
            handedEnts = r.ents;
            handedParts = r.parts;
            if (selfOk) LockSupport.unpark(thread);
        }
        long h = handed;
        JOBQ[(int) (h % RING)] = recIdx;
        handed = h + 1;   // (the slot's contents published with it)
        ringQueuedMax = Math.max(ringQueuedMax, h + 1 - done);
        LockSupport.unpark(thread);
        if (OVERLAP) {   // (A2 test) the frame draws while the main thread goes on; the next frame's first op waits
            pending = true;
            overlapFrames++;
            frames++;
            pollWindow();
            polled();
            return;
        }
        waitPumping(FrameThread::idle);
        waitNs += System.nanoTime() - t0;
        frames++;
        pollWindow();
        polled();
        checkFailure();
    }

    /** RenderSystem.assertOnRenderThread on the main thread: the context borrowed at a safe point (the frame thread idle) */
    public static void borrowIfNeeded() {
        if (!active || mainHolds || Thread.currentThread() != mainThread) return;
        long t0 = System.nanoTime();
        if (BORROW_LOG) {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 3; i < Math.min(st.length, 11); i++) sb.append(i > 3 ? " <- " : "").append(st[i].getClassName().substring(st[i].getClassName().lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            String site = sb.toString();
            BORROW_SITES.computeIfAbsent(site, k -> new long[1])[0]++;
        }
        borrowAsked = true;
        LockSupport.unpark(thread);
        waitPumping(() -> !frameHolds && idle() && !extraRunning);
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);   // (the side context released)
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.setCapabilities(caps);
        setRenderThread(mainThread);
        mainHolds = true;
        borrows++;
        borrowWaitNs += System.nanoTime() - t0;
    }

    private static final boolean BORROW_LOG = !"false".equals(System.getProperty("lab.frameThread.borrowLog"));

    // ------------------------------------------------------------------ the main thread's side context

    private static long side;
    private static org.lwjgl.opengl.GLCapabilities sideCaps;
    private static boolean sideBroken;
    public static long sideFences;

    /** the main thread: the side context current (made on first use; GlWorker's way: a hidden 1x1 window, shared) */
    /**
     * -Dlab.frameThread.side=auto|true|false: the main thread's side context - auto: only with other mods loaded (vanilla
     * makes no raw GL calls there; the context's fence and wait cost ~26-32 us a frame on the host)
     */
    private static final String SIDE = System.getProperty("lab.frameThread.side", "auto");
    private static Boolean sideWanted;
    /**
     * mods whose GL work never runs on the main thread with the frame thread on: no side context for them. Distant
     * Horizons: its render-thread tasks are routed to the frame thread (DhRenderTasksMixin); with the side context its
     * LOD tour lost 102 -> 87 fps, p99 39 -> 68 ms (two contexts of a share group current at once: the driver locks)
     */
    private static final java.util.Set<String> GL_CLEAN = java.util.Set.of("minecraft", "neoforge", "sleepium", "distanthorizons");

    private static boolean sideWanted() {
        Boolean w = sideWanted;
        if (w == null) {
            int mods;
            try {   // (minecraft, neoforge, sleepium, and the mods known to make GL calls only where the frame thread runs them)
                mods = (int) net.minecraftforge.fml.ModList.get().getMods().stream().filter(m -> !GL_CLEAN.contains(m.getModId())).count();
            } catch (Throwable t) {
                mods = 99;
            }
            w = "true".equals(SIDE) || "auto".equals(SIDE) && mods > 0;
            sideWanted = w;
            LOG.info("[sleep/opt] frame thread: side context for the main thread {} ({} mods loaded)", w ? "on" : "off", mods);
        }
        return w;
    }

    private static void sideOn() {
        if (sideBroken || !sideWanted()) return;
        if (side == 0) {
            try {
                org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CLIENT_API, org.lwjgl.glfw.GLFW.GLFW_OPENGL_API);
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API, org.lwjgl.glfw.GLFW.GLFW_NATIVE_CONTEXT_API);
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
                org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT, org.lwjgl.glfw.GLFW.GLFW_TRUE);
                side = org.lwjgl.glfw.GLFW.glfwCreateWindow(1, 1, "sleepium main side", 0L, window);
                org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
            } catch (Throwable t) {
                side = 0;
            }
            if (side == 0) {
                sideBroken = true;
                LOG.warn("[sleep/opt] frame thread: no side context for the main thread (other mods' raw GL there would abort)");
                return;
            }
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(side);
            sideCaps = org.lwjgl.opengl.GL.createCapabilities();
            return;
        }
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(side);
        org.lwjgl.opengl.GL.setCapabilities(sideCaps);
    }

    /**
     * the main thread, handing a frame over: a fence after whatever it sent on the side context (the frame waits on it on
     * the GPU: what the main thread uploaded there) - kept in the frame's slot; the slot's fence before is deleted (the
     * slot is free: its last frame was drawn, its wait issued)
     */
    private static void sideFence(Slot r) {
        if (side == 0) return;
        long old = r.fence;
        r.fence = 0;
        if (old != 0) org.lwjgl.opengl.GL32C.glDeleteSync(old);
        if (mainHolds) return;
        r.fence = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        org.lwjgl.opengl.GL11C.glFlush();
        sideFences++;
    }

    private static void giveBack() {
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
        org.lwjgl.opengl.GL.setCapabilities(null);
        sideOn();
        setRenderThread(thread);
        mainHolds = false;
        borrowAsked = false;
    }

    private static final boolean WINDOWS = org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS;
    /** -Dlab.frameThread.spinUs (0; tried 100 on the host: no gain - wake-ups are 6-15 us): how long either side spins before it parks, waiting for the other */
    private static final long SPIN_NS = Long.getLong("lab.frameThread.spinUs", 0) * 1000L;
    /** the hand-over's cost: the frame thread's wake-up after a job is handed over, the main thread's after a frame */
    private static volatile long handAt, doneAt;
    public static long wakeNs, mainWakeNs, mainWakes;
    private static org.lwjgl.system.windows.MSG msg;

    /**
     * -Dlab.frameThread.eventWait (default on): the main thread waits for the frame thread inside glfwWaitEventsTimeout -
     * asleep until the frame thread's glfwPostEmptyEvent (wakeMain) or the window's own events, on every platform; the
     * window's queue is taken whole meanwhile (Windows never marks it hung) with the input callbacks' work queued as
     * tasks (pumping: FrameThreadPumpQueueMixin). Before: a park of 100 us at a time and a look at sent messages only.
     */
    static final boolean EVENT_WAIT = !"false".equals(System.getProperty("lab.frameThread.eventWait"));
    /** the main thread is inside GLFW's event processing (a callback may borrow the context: no nested wait there) */
    private static boolean inGlfw;
    public static long eventWaits, eventWakes;

    /** the main thread: the window's events, all of them (GLFW); counted for the polls' gaps */
    private static void pollWindow() {
        boolean was = inGlfw;
        inGlfw = true;
        try {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
        } finally {
            inGlfw = was;
        }
    }

    /** the frame thread (any thread but main): the main thread woken - parked or inside glfwWaitEventsTimeout */
    private static void wakeMain() {
        LockSupport.unpark(mainThread);
        if (EVENT_WAIT && mainWaiting) {
            org.lwjgl.glfw.GLFW.glfwPostEmptyEvent();
            eventWakes++;
        }
    }

    private static volatile boolean mainWaiting;

    private static void waitPumping(java.util.function.BooleanSupplier until) {
        if (EVENT_WAIT && !inGlfw && Thread.currentThread() == mainThread) {
            eventWaits++;
            while (!until.getAsBoolean()) {
                serveWorkers();
                mainWaiting = true;
                if (until.getAsBoolean()) break;   // (after the flag: a wake from now on posts an event)
                inGlfw = true;
                boolean was = pumping;
                pumping = true;
                try {
                    org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout(0.002);
                } finally {
                    pumping = was;
                    inGlfw = false;
                    mainWaiting = false;
                }
                polled();
            }
            return;
        }
        if (!WINDOWS) {
            while (!until.getAsBoolean()) LockSupport.parkNanos(100_000L);
            return;
        }
        if (msg == null) msg = org.lwjgl.system.windows.MSG.calloc();
        // a bounded spin first (the hand-over's wake-up is the cost at high frame rates), the window's sent messages
        // looked at every ~50 us and before each park - never a spin without end
        long now = System.nanoTime(), spinUntil = now + SPIN_NS, peekAt = now;
        while (!until.getAsBoolean()) {
            now = System.nanoTime();
            if (now >= peekAt) {
                org.lwjgl.system.windows.User32.PeekMessage(msg, 0L, 0, 0, org.lwjgl.system.windows.User32.PM_NOREMOVE | 0x00400000 /* PM_QS_SENDMESSAGE */);
                peekAt = now + 50_000;
                if (until.getAsBoolean()) break;
            }
            if (now < spinUntil) Thread.onSpinWait();
            else {
                LockSupport.parkNanos(100_000L);
                peekAt = 0;
            }
        }
    }

    // ------------------------------------------------------------------ the frame thread

    /**
     * -Dlab.frameThread.osPriority (2 = Windows' HIGHEST; 0 = leave Java's): the pipeline's priorities (notes/75) - the
     * frame first: the frame thread above the main thread and the server (above normal) and the pools (below normal).
     * It sleeps to its due time most of the frame, so it takes a core only while drawing; on the N100 a woken frame
     * thread waited for a core the generation and the render pool held (the stall probe, 11-16).
     */
    static final int OS_PRIORITY = Integer.getInteger("lab.frameThread.osPriority", 2);

    private static void loop() {
        if (OS_PRIORITY != 0 && RenderPriority.OS_OK) {
            boolean ok = dev.lab.runtime.OsThreads.setCurrentPriority(OS_PRIORITY);
            LOG.info("[sleep/opt] frame thread: OS priority {} {}", OS_PRIORITY, ok ? "set" : "not taken (stays as it was)");
        }
        while (true) {
            long spinUntil = System.nanoTime() + SPIN_NS;   // (bounded: the main thread's next frame usually comes soon)
            while (taken == handed && !borrowAsked) {
                Slot c = cur;
                if (System.nanoTime() < spinUntil) Thread.onSpinWait();
                else if (EXTRA && (SELF && selfOk || mainTicking || EXTRA_ANY && !mainRecording && System.nanoTime() - handAt < 1_000_000_000L) && frameHolds && c != null && c.nOps > 0) {
                    // (no sooner than the frame rate limit allows: extra frames are frames the player asked to cap; when
                    // the main thread is not known to be busy, only once it is half a frame late; self-clocked: at the
                    // limit, or back to back)
                    int cap = mc != null ? mc.getWindow().getFramerateLimit() : 260;
                    long capIv = cap > 0 && cap < 260 ? 1_000_000_000L / cap : 0;
                    long iv = SELF && selfOk ? capInterval() : Math.max(lastFrameNs, capIv);
                    long due = SELF && selfOk ? nextDue - System.nanoTime() : doneAt + (mainTicking ? iv : iv * 3 / 2) - System.nanoTime();
                    if (due > PACE_EARLY) paceSleep(due);
                    else if (extraFrame()) doneAt = System.nanoTime();
                    else paceSleep(500_000L);   // (held by the main thread: look again soon, never spin)
                } else LockSupport.park();
            }
            if (taken == handed && borrowAsked) {   // a borrow: release, wait until handed back (the next job)
                if (frameHolds) {
                    org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
                    org.lwjgl.opengl.GL.setCapabilities(null);
                    frameHolds = false;
                }
                wakeMain();
                while (taken == handed) LockSupport.park();
            }
            if (taken == handed) continue;
            long job = taken;
            drawGen = gen;   // (before any read of the world: the gate's Dekker pair with publish)
            wakeNs += System.nanoTime() - handAt;
            Slot s = SLOTS[JOBQ[(int) (job % RING)]];
            drawIdx = JOBQ[(int) (job % RING)];
            cur = s;
            boolean drop = job < dropBelow;
            try {
                if (!frameHolds) {
                    org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
                    org.lwjgl.opengl.GL.setCapabilities(caps);
                    frameHolds = true;
                }
                drawing = true;
                try {
                    // the main thread's queued GL work first (vanilla's atlas tick queues itself when not on the render
                    // thread): before the frame, where vanilla's tick had it - flipFrame's replay would be a frame late
                    long q0 = System.nanoTime();
                    if (StallProbe.FRAME) StallProbe.frameStart();
                    long sf = s.fence;
                    if (sf != 0) org.lwjgl.opengl.GL32C.glWaitSync(sf, 0, org.lwjgl.opengl.GL32C.GL_TIMEOUT_IGNORED);
                    long q1 = System.nanoTime();
                    tSideWait += q1 - q0;
                    UploadRing.frameBegin();   // (runTick HEAD's on the main thread: GL - the frames-in-flight fence and wait)
                    com.mojang.blaze3d.systems.RenderSystem.replayQueue();
                    long q2 = System.nanoTime();
                    tReplay += q2 - q1;
                    if (s.nDirty > 0 && mc != null) {   // (ring) the section marks the main thread made before this frame's hand-over
                        net.minecraft.client.renderer.LevelRenderer lr = mc.levelRenderer;
                        int[] d = s.dirty;
                        for (int i = 0, n = s.nDirty; i < n; i++)
                            ((dev.lull.opt.FrameThreadDirtyInvoker) lr).sleep$setSectionDirty(d[i * 4], d[i * 4 + 1], d[i * 4 + 2], d[i * 4 + 3] != 0);
                        s.nDirty = 0;
                    }
                    if (SELF) drainSelfCalls();
                    int nCalls = s.calls.size();
                    for (int i = 0; i < nCalls; i++) s.calls.get(i).call(s.callArgs.get(i));   // the main thread's renderer calls, in order
                    s.calls.clear();
                    s.callArgs.clear();
                    runRenderTasks();
                    long q3 = System.nanoTime();
                    tCalls += q3 - q2;
                    long waited = 0;
                    if (SELF && selfOk && !drop && mc != null) {
                        // self-clocked: the frame's preparation above (the queued GL work - the atlas tick -, the marks,
                        // the calls) ran at once, in the idle time before its due time; the drawing waits for it (N100
                        // 11-16: the frame after a tick took 2-3 ms more - 1.4 ms of queued work before its drawing)
                        long iv = capInterval(), w = nextDue - System.nanoTime();
                        if (iv > 0 && w > PACE_EARLY) {
                            prepAhead++;
                            drawGen = Long.MAX_VALUE;   // (nothing of the world read while asleep: the tick's gate need not wait)
                            while ((w = nextDue - System.nanoTime()) > PACE_EARLY && !borrowAsked && !mainHolding) paceSleep(w);
                            drawGen = gen;
                            waited = System.nanoTime() - q3;
                        }
                        paced(System.nanoTime(), iv);
                    } else frameStartAt = System.nanoTime();
                    q3 += waited;
                    recordedAt = System.nanoTime();
                    recordedPt = realPt();
                    if (drop) ringDropped++;   // (handed over before a level change: not drawn)
                    else runOps(s);
                    long dt = System.nanoTime() - q3;
                    tOps += dt;
                    lastFrameNs = dt;
                    long tot = System.nanoTime() - q0 - waited;
                    if (tot > 10_000_000L) {   // the long jobs: what made them long
                        slowJobs++;
                        slowJobNs += tot;
                        slowReplayNs += q2 - q1;
                        slowCallsNs += q3 - q2;
                        slowOpsNs += dt;
                        slowCallsN += nCalls;
                        slowJobMax = Math.max(slowJobMax, tot);
                    }
                } finally {
                    drawing = false;
                }
            } catch (Throwable t) {
                failure = t;
            }
            doneAt = System.nanoTime();
            drawGen = Long.MAX_VALUE;
            taken = job + 1;
            done = taken;
            wakeMain();
        }
    }

    // ------------------------------------------------------------------ extra frames during a long tick (non-exact)

    /**
     * -Dlab.frameThread.extra=true (with overlap; NOT exact): while the main thread runs a client tick and no new frame
     * comes, the frame thread draws the last recorded frame again with a live partial tick (the time since the tick that
     * frame was recorded after, up to 1) - motion goes on interpolating, the camera's turn waits for the main thread's
     * next poll. A long tick no longer stops the frames.
     */
    public static volatile boolean EXTRA = Boolean.getBoolean("lab.frameThread.extra");
    private static volatile boolean mainTicking, extraRunning;
    /** the main thread records a frame (begin .. present): no extra frame may start (they share the op list) */
    private static volatile boolean mainRecording;
    /**
     * -Dlab.frameThread.extraAny (off): an extra frame whenever the main thread is half a frame late, whatever holds it
     * (a light job's join, a mod's work) - not only in a tick or the queued tasks. Lost on the N100 (tour at 120 fps:
     * p99 15.6 -> 37 ms): an extra frame there takes 6-8 ms, and a main thread only a little late then waits for it.
     */
    public static final boolean EXTRA_ANY = Boolean.getBoolean("lab.frameThread.extraAny");
    private static boolean extraNow;
    private static long recordedAt, lastFrameNs = 500_000;
    private static float recordedPt;
    public static long extraFrames, extraNs;

    /** Minecraft.tick HEAD / RETURN on the main thread (FrameThreadMixin) */
    public static long ticksSeen, ticksWhileFrameBusy, ticksWhileIdle;

    /**
     * -Dlab.frameThread.extraTurn=true (with extra frames): an extra frame turns its camera by the cursor's move since the
     * tick began (GetCursorPos - the window's input is only read by the main thread, which is busy) through vanilla's
     * sensitivity formula; the player itself turns when the main thread polls after the tick (first person only)
     */
    public static final boolean EXTRA_TURN = Boolean.getBoolean("lab.frameThread.extraTurn");
    private static volatile int tickCursorX, tickCursorY;
    private static volatile double turnScale;
    private static volatile boolean turnOk;
    public static volatile float turnYaw, turnPitch;
    private static org.lwjgl.system.windows.POINT cursorMain, cursorFrame;

    /** main thread at a tick's start: the turn factors now (and the cursor, for the old GetCursorPos way) */
    private static void turnStart() {
        turnParams();
        if (!turnOk || MOUSE_ACC || !WINDOWS) return;
        if (cursorMain == null) cursorMain = org.lwjgl.system.windows.POINT.calloc();
        org.lwjgl.system.windows.User32.GetCursorPos(cursorMain);
        tickCursorX = cursorMain.x();
        tickCursorY = cursorMain.y();
    }

    /** main thread: vanilla's turn factors (sensitivity, inverted pitch) and whether the camera turns with the mouse now */
    private static void turnParams() {
        if (!(EXTRA_TURN || LATCH) || mc == null || mc.player == null) {
            turnOk = false;
            return;
        }
        double d2 = mc.options.sensitivity().get() * 0.6 + 0.2;
        double s = d2 * d2 * d2 * 8.0 * 0.15;
        turnScale = mc.options.invertYMouse().get() ? -s : s;   // (the sign of the pitch only: undone for yaw in turnNow)
        turnOk = mc.mouseHandler.isMouseGrabbed() && !mc.options.smoothCamera && mc.options.getCameraType().isFirstPerson() && !mc.player.isScoping();
    }

    /**
     * -Dlab.frameThread.latchTurn (notes/75 stage 2): every frame the frame thread draws turns its camera by the mouse
     * movement the main thread has taken in and not yet applied (not only the extra frames) - read with the player's
     * rotation as one step against the main thread's apply (TURN_LOCK): the view as fresh as the frame's start, however
     * busy the main thread is or deep the ring.
     */
    public static final boolean LATCH = Boolean.getBoolean("lab.frameThread.latchTurn");
    /** MouseHandler.handleAccumulatedMovement (main) and Camera.setup on the frame thread */
    public static final java.util.concurrent.locks.ReentrantLock TURN_LOCK = new java.util.concurrent.locks.ReentrantLock();

    /** the frame thread, Camera.setup's end (under TURN_LOCK): this frame's turn into turnYaw / turnPitch; false: none */
    public static boolean turnHere(boolean extra) {
        if (!(extra && EXTRA_TURN || LATCH && onFrameThread())) return false;
        turnNow();
        return true;
    }



    /** frame thread, an extra frame: the camera turn since the tick began (degrees), into turnYaw / turnPitch */
    private static void turnNow() {
        turnYaw = 0f;
        turnPitch = 0f;
        if (!turnOk) return;
        double k = Math.abs(turnScale);
        if (MOUSE_ACC) {   // the movement the main thread will apply at its next frame (pumpMotion keeps it current)
            dev.lull.opt.FrameThreadMouseAccessor m = (dev.lull.opt.FrameThreadMouseAccessor) mc.mouseHandler;
            double dx = m.sleep$accumulatedDX(), dy = m.sleep$accumulatedDY();
            if (pumpValid) {   // the moves polled inside the tick, queued: GLFW's cursor past what the handler has seen
                dx += pumpCursorX - m.sleep$xpos();
                dy += pumpCursorY - m.sleep$ypos();
            }
            turnYaw = (float) (dx * k);
            turnPitch = (float) (dy * turnScale);
            return;
        }
        if (cursorFrame == null) cursorFrame = org.lwjgl.system.windows.POINT.calloc();
        org.lwjgl.system.windows.User32.GetCursorPos(cursorFrame);
        turnYaw = (float) ((cursorFrame.x() - tickCursorX) * k);
        turnPitch = (float) ((cursorFrame.y() - tickCursorY) * turnScale);
    }

    /**
     * -Dlab.frameThread.mouseAcc (default on): an extra frame's turn from the mouse movement Minecraft has taken in and not
     * yet applied (MouseHandler's accumulated delta), kept current during a long tick by pumpMotion. The cursor position
     * (GetCursorPos) barely moves under GLFW's captured cursor with raw input: the hand test 11-16 showed 2.7 degrees in
     * the extra frames, then a jump of 32-39 at the next recorded frame - the camera turned in steps at the tick rate.
     */
    static final boolean MOUSE_ACC = !"false".equals(System.getProperty("lab.frameThread.mouseAcc"));
    private static long pumpAt;
    /**
     * -Dlab.frameThread.pumpAfterMs (8): the mouse's messages are taken only once a tick has run this long. Taken in every
     * tick (every 1 ms between entity ticks) they cost the host's horde ~10 % fps and Windows marked the window as not
     * responding at times (11-16: 1 of 130 s watched vs 0 of 217 s without; with the 30-ms test tick often).
     */
    private static final long PUMP_AFTER_NS = (long) (Double.parseDouble(System.getProperty("lab.frameThread.pumpAfterMs", "8")) * 1e6);
    /** when the main thread's current busy stretch (queued tasks, then the tick) began */
    private static volatile long busySince;
    public static long pumps;
    /** the main thread polls the window inside a long tick: Minecraft.execute queues (FrameThreadPumpQueueMixin) */
    public static volatile boolean pumping;
    private static double[] pumpX, pumpY;
    /** GLFW's cursor after the last poll inside a tick (valid until the queued moves are run: the next tasks) */
    private static volatile double pumpCursorX, pumpCursorY;
    private static volatile boolean pumpValid;

    /**
     * the main thread inside a long tick (entity ticks, the test load): the window's mouse-movement messages only
     * (WM_INPUT, WM_MOUSEMOVE) taken from the queue at most every 1 ms - GLFW's handler passes them to MouseHandler.onMove,
     * which adds them to the accumulated delta. Keys, clicks, the window's own messages wait for the usual poll.
     */
    public static void pumpMotion() {
        if (!active || !(EXTRA_TURN || LATCH) || !MOUSE_ACC || !mainTicking || Thread.currentThread() != mainThread) return;
        long now = System.nanoTime();
        if (now - busySince < PUMP_AFTER_NS) return;   // (only in a long tick: in a normal one the next poll comes soon anyway)
        if (now - pumpAt < 1_000_000L) return;
        pumpAt = now;
        // a whole poll (the mouse's messages alone, taken with a filtered PeekMessage, made Windows mark the window as not
        // responding: 22 of 131 s watched with a 30-ms tick vs 0) - the input callbacks' work queued meanwhile
        // (FrameThreadPumpQueueMixin), GLFW's virtual cursor read after it for the extra frames' turn
        pumping = true;
        try {
            pollWindow();
            polled();
        } finally {
            pumping = false;
        }
        if (pumpX == null) {
            pumpX = new double[1];
            pumpY = new double[1];
        }
        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, pumpX, pumpY);
        pumpCursorX = pumpX[0];
        pumpCursorY = pumpY[0];
        pumpValid = true;
        pumps++;
    }

    /** a frame or an extra frame is drawing on the frame thread now */
    public static boolean drawingNow() {
        return active && (!idle() || extraRunning);
    }

    public static boolean extraNow() {
        return extraNow && Thread.currentThread() == thread;
    }

    /**
     * -Dlab.frameThread.extraTasks (default on with extra frames): extra frames also while the main thread runs the
     * queued tasks (runAllTasks: the packets - chunk data on the N100 takes 20-40 ms in a frame while terrain loads).
     * The overlapped frame already draws while those tasks run (A2); an extra frame is that frame again, later.
     */
    public static final boolean EXTRA_TASKS = !"false".equals(System.getProperty("lab.frameThread.extraTasks"));
    /** the frame count when the camera turn's reference was taken (once per stretch the main thread is busy) */
    private static long turnFrame = -1;
    public static long tasksSeen;

    public static void tasksStart() {
        pumpValid = false;   // (the queued moves run now: the handler catches up with the cursor)
        taskPending = false;
        walkReady = false;
        runTickAt = System.nanoTime();
        if (!EXTRA_TASKS || !EXTRA) return;
        if (!mainTicking) busySince = System.nanoTime();
        mainTicking = true;
        if (turnFrame != frames) {
            turnStart();
            turnFrame = frames;
        }
        tasksSeen++;
        if (active) LockSupport.unpark(thread);
    }

    public static void tasksEnd() {
        if (!EXTRA_TASKS || !EXTRA) return;
        mainTicking = false;
    }

    // ------------------------------------------------------------------ frames in the middle of a client tick

    /** the client tick an entity was last ticked in (FrameThreadEntityMarkMixin) */
    public interface Marked {
        int sleep$ftRound();

        void sleep$setFtRound(int r);
    }

    /**
     * -Dlab.frameThread.midTick (default on): a frame drawn while the main thread runs a client tick (the overlap, extra
     * frames) draws each entity the tick has reached at partial tick 0 - its last tick's end (xo..: set first thing in
     * its tick) - and the others at the frame's own partial tick (from the tick's end: near 1 by now, the same place).
     * Without it the entities ticked already were drawn a tick ahead until the tick ended, then stepped back: the zoo's
     * sniffer raised its head for one frame every few ticks (11-16, a frame burst; with every engine path off but the
     * frame thread too). The same for the camera's entity.
     */
    static final boolean MID_TICK = !"false".equals(System.getProperty("lab.frameThread.midTick"));
    /** the client tick running now (or last), counted at its start on the main thread */
    private static volatile int tickRound = 1;
    private static volatile boolean inClientTick;
    public static long midTickDraws;

    /** the main thread: e is about to tick (ClientLevel.tickNonPassenger / tickPassenger) */
    public static void ticking(net.minecraft.world.entity.Entity e) {
        if (MID_TICK && active && inClientTick) ((Marked) e).sleep$setFtRound(tickRound);
    }

    /** an entity's partial tick in a frame: 0 if the frame is on the frame thread and the client tick running has ticked it */
    public static float partial(net.minecraft.world.entity.Entity e, float pt) {
        if (!MID_TICK || !inClientTick || e == null || !drawingHere() || ((Marked) e).sleep$ftRound() != tickRound) return pt;
        midTickDraws++;
        return 0f;
    }

    public static void tickStart() {
        lastTickStartNs = System.nanoTime();
        if (MID_TICK) {
            tickRound++;
            inClientTick = true;
        }
        if (!mainTicking) busySince = System.nanoTime();
        mainTicking = true;
        if (turnFrame != frames) {   // (the queued tasks before it already took the turn's reference for this frame)
            turnStart();
            turnFrame = frames;
        }
        ticksSeen++;
        if (idle()) ticksWhileIdle++;
        else ticksWhileFrameBusy++;
        if (EXTRA && active) LockSupport.unpark(thread);
    }

    /**
     * -Dsleep.test.tickLoadMs=N (test only): every client tick keeps the main thread busy N ms (a busy wait, as a heavy
     * mod's tick would) - a long tick on vanilla, to measure what the frame thread gives against it
     */
    private static final double TEST_TICK_MS = Double.parseDouble(System.getProperty("sleep.test.tickLoadMs", "0"));

    public static void testTickLoad() {
        if (TEST_TICK_MS <= 0 || mc == null && Minecraft.getInstance().level == null) return;
        long until = System.nanoTime() + (long) (TEST_TICK_MS * 1e6);
        while (System.nanoTime() < until) {
            pumpMotion();
            Thread.onSpinWait();
        }
    }

    public static void tickEnd() {
        tickedSinceHand = true;
        mainTicking = false;
        if (PUBLISH && active && OVERLAP && mc != null && Thread.currentThread() == mainThread) publishParticles();
        tickEndNs = System.nanoTime();
        inClientTick = false;   // (after the new tick end: the frames' partial tick starts from 0 again)
    }

    private static volatile boolean mainHolding;
    public static long mainHolds2;

    /**
     * the main thread is about to change what a drawing frame may read (TickAsync's join: its snapshots and pairs): no
     * frame or extra frame drawing from here until releaseForMain (it waits for the one drawing now)
     */
    /** holds nested (a screen set inside a resize, allChanged inside both): the frames come back after the outermost */
    private static int holdDepth;

    public static void holdForMain() {
        if (!active || Thread.currentThread() != mainThread) return;
        holdDepth++;
        mainHolding = true;
        mainHolds2++;
        awaitLast();
        if (extraRunning) waitPumping(() -> !extraRunning);
        replayDirty();
    }

    /** the main thread, no frame or extra frame drawing (begin, holdForMain): the marks queued meanwhile, in order */
    private static void replayDirty() {
        if (nDirty == 0 || mc == null) return;
        int n = nDirty;
        nDirty = 0;
        net.minecraft.client.renderer.LevelRenderer lr = mc.levelRenderer;
        mainRecordingReplay = true;
        try {
            for (int i = 0; i < n; i++) {
                int o = i * 4;
                ((dev.lull.opt.FrameThreadDirtyInvoker) lr).sleep$setSectionDirty(DIRTY[o], DIRTY[o + 1], DIRTY[o + 2], DIRTY[o + 3] != 0);
            }
        } finally {
            mainRecordingReplay = false;
        }
    }

    /** replayDirty's calls go through (not queued again) */
    private static boolean mainRecordingReplay;

    public static void releaseForMain() {
        if (Thread.currentThread() != mainThread || holdDepth == 0) return;
        if (--holdDepth == 0 && !holdToFrame) mainHolding = false;   // (a level change's hold lasts until the next frame)
    }

    private static boolean holdToFrame;

    /**
     * a level change (FrameReactor's LEVEL_CHANGE: disconnect, a new level): no frame and no extra frame from the old
     * level until the main thread hands a new frame over (the extra frames' "main thread late" would draw it otherwise)
     */
    public static void holdUntilNextFrame() {
        if (!active || Thread.currentThread() != mainThread) return;
        if (RING > 1) dropBelow = handed;   // (the frames handed over and not begun: not drawn - their calls are made)
        holdForMain();
        if (holdToFrame) holdDepth--;   // (one level-change hold at a time: the next hand-over ends it)
        holdToFrame = true;
    }

    /** the partial tick going on with the clock since the recorded frame (capped at 1) */
    private static float livePt() {
        return Math.min(1f, recordedPt + (System.nanoTime() - recordedAt) / 50_000_000f);
    }

    /** a recorded call's arguments with its partial tick (1.20.1: a float argument) the frame's own */
    private static Object[] live(Object[] args) {
        Object[] a = args.clone();
        float pt = PT_TICK_END ? tickEndPt() : livePt();
        for (int i = 0; i < a.length; i++) if (a[i] instanceof Float) a[i] = pt;
        return a;
    }

    /**
     * -Dlab.frameThread.ptFromTickEnd (non-exact, in lab.decoupled): every frame drawn on the frame thread takes its
     * partial tick from the END of the last client tick, not its start: (now - tick end) / 50 ms, capped at 1. Vanilla's
     * (from the start) reaches 1 as the next tick begins and stays there while it runs - with a long tick the world stood
     * still in the extra frames, then jumped (1.21.1 hand test 11-16). Every lerp by the partial tick follows (mods'
     * entities too: Minecraft.getFrameTime answers with it on the frame thread, FrameThreadTimerMixin).
     */
    public static final boolean PT_TICK_END = Boolean.getBoolean("lab.frameThread.ptFromTickEnd");
    private static volatile long tickEndNs;

    /** the partial tick from the last client tick's end (paused, or before the first tick: Minecraft's own) */
    public static float tickEndPt() {
        long end = tickEndNs;
        if (end == 0 || mc == null || mc.isPaused()) return realPt();
        return Math.max(0f, Math.min(1f, (System.nanoTime() - end) / 50_000_000f));
    }

    /** Minecraft's own partial tick (its timer or the paused one), read past the frame thread's getFrameTime */
    public static float realPt() {
        Minecraft m = mc != null ? mc : Minecraft.getInstance();
        dev.lull.opt.FrameThreadTimerAccessor a = (dev.lull.opt.FrameThreadTimerAccessor) m;
        return m.isPaused() ? a.sleep$pausePartialTick() : a.sleep$timer().partialTick;
    }

    /** the frame thread, idle: an extra frame if the main thread is in a tick and the last frame is older than one frame */
    private static boolean extraFrame() {
        // (ring: the slot drawn last is never recorded into - the main thread's recording does not stop it)
        Slot c = cur;
        boolean rec1 = RING == 1 && mainRecording;
        boolean self = SELF && selfOk;
        if (!EXTRA || !OVERLAP || !(self || mainTicking || EXTRA_ANY) || rec1 || borrowAsked || mainHolding || c == null || c.nOps == 0 || taken != handed) return false;
        extraRunning = true;
        if (taken != handed || borrowAsked || mainHolding || RING == 1 && mainRecording) {   // (a job or a borrow came meanwhile)
            extraRunning = false;
            return false;
        }
        drawGen = gen;
        long t0 = System.nanoTime();
        if (self) paced(t0, capInterval());
        else frameStartAt = t0;
        drawing = true;
        extraNow = true;
        try {
            UploadRing.frameBegin();
            if (self) {   // (a frame of its own: the queued GL work and mods' render-thread tasks too, as a job's start)
                selfFrames++;
                if (StallProbe.FRAME) StallProbe.frameStart();
                com.mojang.blaze3d.systems.RenderSystem.replayQueue();
                drainSelfCalls();
                runRenderTasks();
            }
            runOps(c);
        } catch (Throwable t) {
            failure = t;
        } finally {
            extraNow = false;
            drawing = false;
        }
        extraFrames++;
        extraNs += System.nanoTime() - t0;
        drawGen = Long.MAX_VALUE;
        extraRunning = false;
        wakeMain();
        return true;
    }

    private static void runOps(Slot s) {
        Minecraft m = mc;
        final int[] OPS = s.ops, ARG_I = s.argI, ARG_J = s.argJ;
        final boolean[] ARG_Z = s.argZ;
        for (int k = 0, n = s.nOps; k < n; k++) {
            if (OPS[k] == 0) {   // a wrapped call: its Operation with the recorded arguments
                s.operations[k].call(extraNow || PT_TICK_END ? live(s.args[k]) : s.args[k]);
                continue;
            }
            switch (OPS[k]) {
                case CLEAR -> com.mojang.blaze3d.systems.RenderSystem.clear(ARG_I[k], ARG_Z[k]);
                case BIND_WRITE -> m.getMainRenderTarget().bindWrite(ARG_Z[k]);
                case SETUP_NO_FOG -> net.minecraft.client.renderer.FogRenderer.setupNoFog();
                case ENABLE_CULL -> com.mojang.blaze3d.systems.RenderSystem.enableCull();
                case FRAME_PRE -> net.minecraftforge.event.ForgeEventFactory.onRenderTickStart(PT_TICK_END ? tickEndPt() : realPt());
                case RENDER -> m.gameRenderer.render(PT_TICK_END ? tickEndPt() : realPt(), net.minecraft.Util.getNanos(), ARG_Z[k]);
                case FRAME_POST -> net.minecraftforge.event.ForgeEventFactory.onRenderTickEnd(PT_TICK_END ? tickEndPt() : realPt());
                case UNBIND_WRITE -> m.getMainRenderTarget().unbindWrite();
                case BLIT -> m.getMainRenderTarget().blitToScreen(ARG_I[k], ARG_J[k]);
                case UPDATE_DISPLAY -> m.getWindow().updateDisplay();
                default -> throw new IllegalStateException("frame op " + OPS[k]);
            }
        }
        shownFrames++;
        if (burstLeft > 0) burstShot(m);   // (the frame shown: the main target still holds it)
    }

    // ------------------------------------------------------------------ a burst of shown frames (test: ClientAuto burst)

    private static volatile int burstLeft;
    private static int burstIdx;
    private static java.nio.file.Path burstDir;
    private static String burstLabel;
    private static java.io.PrintWriter burstCsv;
    private static java.util.concurrent.ExecutorService burstIo;

    /** the main thread (ClientAuto): the next n frames shown are written to dir as burst-<label>-<k>-<j|x>.png */
    public static boolean requestBurst(int n, java.nio.file.Path dir, String label) {
        if (!active || burstLeft > 0) return false;
        try {
            java.nio.file.Files.createDirectories(dir);
            burstCsv = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(dir.resolve("burst-" + label + ".csv")));
            burstCsv.println("k,kind,ns,gen,slot,job");
        } catch (java.io.IOException e) {
            return false;
        }
        if (burstIo == null) burstIo = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Sleepium burst writer");
            t.setDaemon(true);
            return t;
        });
        burstDir = dir;
        burstLabel = label;
        burstIdx = 0;
        burstLeft = n;
        return true;
    }

    /** the frame thread, a frame drawn and about to be shown: its picture (written on another thread) and its timing */
    private static void burstShot(Minecraft m) {
        int k = burstIdx++;
        boolean x = extraNow;
        com.mojang.blaze3d.platform.NativeImage img = net.minecraft.client.Screenshot.takeScreenshot(m.getMainRenderTarget());
        java.nio.file.Path out = burstDir.resolve(String.format(java.util.Locale.ROOT, "burst-%s-%03d-%s.png", burstLabel, k, x ? "x" : "j"));
        burstCsv.println(k + "," + (x ? "x" : "j") + "," + System.nanoTime() + "," + gen + "," + drawIdx + "," + taken);
        burstIo.execute(() -> {
            try (img) {
                img.writeToFile(out);
            } catch (Exception e) {
                LOG.warn("[sleep/opt] burst shot {} failed: {}", out.getFileName(), e.toString());
            }
        });
        if (--burstLeft == 0) {
            burstCsv.close();
            LOG.info("[sleep/opt] burst: {} frames -> {}", k + 1, burstDir);
        }
    }

    /** the measured window's CPU of the main thread and the frame thread (ns), taken at its first hand-over */
    private static long cpuMain0 = -1, cpuFrame0, cpuAt0;

    private static void cpuMark() {
        if (cpuMain0 >= 0 || !dev.lull.ClientMeter.measuring()) return;
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        cpuMain0 = mx.getThreadCpuTime(mainThread.getId());
        cpuFrame0 = mx.getThreadCpuTime(thread.getId());
        cpuAt0 = System.nanoTime();
    }

    /** frames the frame thread drew (recorded, extra, its own), for Minecraft's FPS counter */
    private static volatile long shownFrames;
    private static long shownRead;

    /**
     * runTick's once-a-second FPS (FrameThreadFpsMixin): the frames drawn since the last time - not the main thread's
     * loops, which with self-clocked frames come only for ticks, packets and the mouse (the HUD showed 68 at ~1100 fps)
     */
    public static int fpsFrames(int loops) {
        if (!active) return loops;
        long n = shownFrames;
        int d = (int) (n - shownRead);
        shownRead = n;
        return d;
    }

    public static String report() {
        if (!ON) return "";
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "; frame thread: frames %d (%.3f ms a frame waited), overlapped %d (%.3f ms waited before the next), hand-over wake-ups: the frame thread %.1f us, the main thread %.1f us, borrows %d (%.3f ms each), failures %d",
                frames + extraFrames, waitNs / 1e6 / Math.max(1, frames), overlapFrames, overlapWaitNs / 1e6 / Math.max(1, overlapFrames), wakeNs / 1e3 / Math.max(1, frames),
                mainWakeNs / 1e3 / Math.max(1, mainWakes), borrows, borrowWaitNs / 1e6 / Math.max(1, borrows), failures));
        long fr = Math.max(1, frames);
        if (NO_WAIT) b.append("; frames skipped by the main thread (no wait) ").append(skippedFrames);
        b.append(pollGaps());
        if (MID_TICK) b.append("; entities drawn at their last tick's end (mid-tick) ").append(midTickDraws);
        if (cpuMain0 >= 0 && thread != null) {
            var mx = java.lang.management.ManagementFactory.getThreadMXBean();
            double secs = (System.nanoTime() - cpuAt0) / 1e9;
            b.append(String.format(java.util.Locale.ROOT, "; CPU in the window: main thread %.1f ms/s, frame thread %.1f ms/s",
                    (mx.getThreadCpuTime(mainThread.getId()) - cpuMain0) / 1e6 / secs, (mx.getThreadCpuTime(thread.getId()) - cpuFrame0) / 1e6 / secs));
        }
        if (SELF) b.append(String.format(java.util.Locale.ROOT, "; self-clocked: prepared ahead %d; frames of its own %d, hand-overs %d (level %d, calls %d, marks %d, tick %d, lists %d, 50 ms %d), skipped %d; main idle waits %d (woken: task %d, walk %d, mouse %d, tick due %d, screen %d)",
                prepAhead, selfFrames, selfHands, HAND_WHY[0], HAND_WHY[1], HAND_WHY[2], HAND_WHY[3], HAND_WHY[4], HAND_WHY[5], selfSkips, idleWaits, idleWakesTask, idleWakesWalk, idleWakesMouse, idleWakesDue, idleWakesScreen));
        if (PUBLISH) b.append("; published lists: entities ").append(entPublishes).append(", particles ").append(partPublishes);
        if (RING > 1) b.append(String.format(java.util.Locale.ROOT, "; ring of %d: the main thread waited for a free slot %d times (%.3f ms each), most frames waiting or drawing %d, dropped at level changes %d",
                RING, ringWaits, ringWaitNs / 1e6 / Math.max(1, ringWaits), ringQueuedMax, ringDropped));
        if (EVENT_WAIT) b.append("; event waits ").append(eventWaits).append(" (woken by the frame thread ").append(eventWakes).append(")");
        if (EXTRA) b.append(String.format(java.util.Locale.ROOT, "; extra frames during ticks %d (%.3f ms each); ticks %d (the frame thread busy at their start %d, idle %d)",
                extraFrames, extraNs / 1e6 / Math.max(1, extraFrames), ticksSeen, ticksWhileFrameBusy, ticksWhileIdle));
        b.append(String.format(java.util.Locale.ROOT, "; per frame (us): main present: polls %.1f, side fence %.1f, held lists + light %.1f; frame thread: side wait %.1f, frameBegin + queue %.1f, calls %.1f, ops %.1f",
                tPoll / 1e3 / fr, tFence / 1e3 / fr, tHeld / 1e3 / fr, tSideWait / 1e3 / fr, tReplay / 1e3 / fr, tCalls / 1e3 / fr, tOps / 1e3 / fr));
        if (renderTasks > 0) b.append("; mods' render-thread tasks run here ").append(renderTasks).append(" (failed ").append(renderTaskFailures).append(")");
        if (slowJobs > 0) b.append(String.format(java.util.Locale.ROOT, "; frame jobs over 10 ms: %d (%.1f ms each, longest %.1f; frameBegin + queue %.1f, calls %.1f (%d calls each), ops %.1f)",
                slowJobs, slowJobNs / 1e6 / slowJobs, slowJobMax / 1e6, slowReplayNs / 1e6 / slowJobs, slowCallsNs / 1e6 / slowJobs, slowCallsN / slowJobs, slowOpsNs / 1e6 / slowJobs));
        BORROW_SITES.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(8)
                .forEach(e -> b.append("; borrow ").append(e.getValue()[0]).append(" x ").append(e.getKey()));
        return b.toString();
    }
}
