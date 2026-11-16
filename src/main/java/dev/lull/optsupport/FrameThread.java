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
    private static volatile boolean borrowAsked, jobDone = true, drawing;
    private static volatile Throwable failure;

    // the recorded render half: op codes and their arguments
    public static final int CLEAR = 1, BIND_WRITE = 2, SETUP_NO_FOG = 3, ENABLE_CULL = 4, FRAME_PRE = 5, RENDER = 6, FRAME_POST = 7,
            UNBIND_WRITE = 8, BLIT = 9, UPDATE_DISPLAY = 10;
    private static final int[] OPS = new int[32];
    private static final int[] ARG_I = new int[32], ARG_J = new int[32];
    private static final boolean[] ARG_Z = new boolean[32];
    private static int nOps;
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
        realTimer = m.getTimer();
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
    /** the entity list as the main thread had it when the frame was handed over (A2: the frame walks this) */
    private static final java.util.ArrayList<net.minecraft.world.entity.Entity> HELD = new java.util.ArrayList<>();
    private static boolean heldValid;

    /** the light point (ClientLightAsyncMixin's, set when that mixin's class loads) - null: vanilla's two calls */
    public static java.util.function.Consumer<net.minecraft.client.multiplayer.ClientLevel> lightPoint;

    /** renderLevel's light point is skipped on the frame thread under OVERLAP: the main thread did it before the hand-over */
    public static boolean lightOnMain() {
        return heldValid && drawingHere();
    }

    private static final java.util.LinkedHashMap<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<net.minecraft.client.particle.Particle>> HELD_PARTICLES = new java.util.LinkedHashMap<>();

    /** the frame thread under OVERLAP: the particle queues held for this frame, else null (the live ones) */
    public static java.util.Map<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<net.minecraft.client.particle.Particle>> heldParticles() {
        return heldValid && drawingHere() ? HELD_PARTICLES : null;
    }

    /** the frame thread under OVERLAP: the entity list held for this frame, else null (the live one) */
    public static Iterable<net.minecraft.world.entity.Entity> heldEntities() {
        return heldValid && drawingHere() ? HELD : null;
    }
    public static long overlapFrames, overlapWaitNs;

    /** sections the main thread marked dirty while a frame drew: x, y, z, flags (bit 0: re-render on the main thread) */
    private static int[] DIRTY = new int[4 * 256];
    private static int nDirty;
    public static long deferredDirty;

    /** LevelRenderer.setSectionDirty on the main thread while a frame draws (OVERLAP): queued (true), else false */
    public static boolean deferDirty(int x, int y, int z, boolean mainThread) {
        if (!pending || Thread.currentThread() != mainThread() || jobDone) return false;
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

    /** the calls handed over with a frame: made by the frame thread at its job's start, before it draws */
    private static final java.util.ArrayList<com.llamalad7.mixinextras.injector.wrapoperation.Operation<?>> JOB_CALLS = new java.util.ArrayList<>();
    private static final java.util.ArrayList<Object[]> JOB_CALL_ARGS = new java.util.ArrayList<>();

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

    private static Thread mainThread() {
        return mainThread;
    }

    /** the main thread before it records a new frame: the last one (still drawing under OVERLAP) done */
    private static void awaitLast() {
        if (!pending) return;
        long t0 = System.nanoTime();
        waitPumping(() -> jobDone);
        long w = System.nanoTime() - doneAt;
        if (w >= 0 && w < 100_000_000L) {
            mainWakeNs += w;
            mainWakes++;
        }
        pending = false;
        overlapWaitNs += System.nanoTime() - t0;
        if (nDirty > 0) {   // the marks queued meanwhile, now (the frame done: nothing reads the dirty sets)
            int n = nDirty;
            nDirty = 0;
            net.minecraft.client.renderer.LevelRenderer lr = mc.levelRenderer;
            for (int i = 0; i < n; i++) {
                int o = i * 4;
                ((dev.lull.opt.FrameThreadDirtyInvoker) lr).sleep$setSectionDirty(DIRTY[o], DIRTY[o + 1], DIRTY[o + 2], DIRTY[o + 3] != 0);
            }
        }
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

    private static void begin() {
        recordSeq++;
        awaitLast();
        mainRecording = true;   // (before the check: an extra frame starting now sees it and backs off)
        if (extraRunning) waitPumping(() -> !extraRunning);
        nOps = 0;
        fresh = false;
    }

    /** the recorded calls as the wrapped Operations (other mods' wrappers included) and their arguments */
    @SuppressWarnings("rawtypes")
    private static final com.llamalad7.mixinextras.injector.wrapoperation.Operation[] OPERATIONS = new com.llamalad7.mixinextras.injector.wrapoperation.Operation[32];
    private static final Object[][] ARGS = new Object[32][];

    public static void record(com.llamalad7.mixinextras.injector.wrapoperation.Operation<?> operation, Object... args) {
        if (fresh) begin();
        OPERATIONS[nOps] = operation;
        ARGS[nOps] = args;
        OPS[nOps++] = 0;
    }

    public static void op(int code) {
        if (fresh) begin();
        OPS[nOps++] = code;
    }

    public static void op(int code, int i, int j, boolean z) {
        if (fresh) begin();
        ARG_I[nOps] = i;
        ARG_J[nOps] = j;
        ARG_Z[nOps] = z;
        OPS[nOps++] = code;
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
        long p0 = System.nanoTime();
        long seq = recordSeq;
        // this frame's op list is complete: a runTick nested in the poll below begins a list of its own (begin: recordSeq
        // moves) - without this its ops were appended to this frame's and handed over with it (the exit crash, 11-16)
        fresh = true;
        org.lwjgl.glfw.GLFW.glfwPollEvents();
        if (recordSeq != seq) {
            // the poll ran a runTick of its own (a click on "Save and Quit": Minecraft.disconnect's forced frames) - it
            // recorded over this frame's ops and handed its frames over; this one, of the world before, is dropped
            // (11-16 hand test: it was drawn after gameMode was gone, an NPE in renderItemInHand)
            nestedDropped++;
            return;
        }
        if (mainHolds) giveBack();
        long p1 = System.nanoTime();
        tPoll += p1 - p0;
        sideFence();
        tFence += System.nanoTime() - p1;
        FrameRandom.install(mc.level);   // (the level's random per thread: render code may draw from it)
        JOB_CALLS.addAll(CALLS);
        JOB_CALL_ARGS.addAll(CALL_ARGS);
        CALLS.clear();
        CALL_ARGS.clear();
        long t0 = System.nanoTime();
        fresh = true;
        long h0 = System.nanoTime();
        if (OVERLAP) {   // the world's lists the frame walks, as they are now (the main thread changes them meanwhile)
            HELD.clear();
            if (mc.level != null) for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) HELD.add(e);
            HELD_PARTICLES.clear();
            for (var en : ((dev.lull.opt.ParticleEngineAccessor) mc.particleEngine).sleep$particles().entrySet())
                HELD_PARTICLES.put(en.getKey(), new java.util.ArrayDeque<>(en.getValue()));
            heldValid = mc.level != null;
            if (mc.level != null && mc.getConnection() != null && mc.getConnection().getLevel() == mc.level) {   // renderLevel's light point, here (packets and the tick may change the light engine while the frame draws)
                if (lightPoint != null) lightPoint.accept(mc.level);
                else {
                    mc.level.pollLightUpdates();
                    mc.level.getChunkSource().getLightEngine().runLightUpdates();
                }
            }
        } else heldValid = false;
        tHeld += System.nanoTime() - h0;
        handAt = System.nanoTime();
        mainRecording = false;
        if (holdToFrame) {
            holdToFrame = false;
            mainHolding = false;
        }
        jobDone = false;
        LockSupport.unpark(thread);
        if (OVERLAP) {   // (A2 test) the frame draws while the main thread goes on; the next frame's first op waits
            pending = true;
            overlapFrames++;
            frames++;
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            return;
        }
        waitPumping(() -> jobDone);
        waitNs += System.nanoTime() - t0;
        frames++;
        org.lwjgl.glfw.GLFW.glfwPollEvents();
        Throwable f = failure;
        if (f != null) {
            failure = null;
            failures++;
            if (f instanceof RuntimeException r) throw r;
            if (f instanceof Error e) throw e;
            throw new RuntimeException(f);
        }
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
        waitPumping(() -> !frameHolds && jobDone && !extraRunning);
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
    /** the side context's fence at a hand-over: the frame waits for it on the GPU (what the main thread uploaded there) */
    private static volatile long sideFence;
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
                mods = (int) net.neoforged.fml.ModList.get().getMods().stream().filter(m -> !GL_CLEAN.contains(m.getModId())).count();
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

    /** the main thread, handing a frame over: a fence after whatever it sent on the side context (the frame waits on it) */
    private static void sideFence() {
        if (side == 0 || mainHolds) return;
        long f = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        org.lwjgl.opengl.GL11C.glFlush();
        long old = sideFence;
        sideFence = f;
        sideFences++;
        if (old != 0) org.lwjgl.opengl.GL32C.glDeleteSync(old);   // (the frame before waited on it already: present waits for that frame first)
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

    private static void waitPumping(java.util.function.BooleanSupplier until) {
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

    private static void loop() {
        while (true) {
            long spinUntil = System.nanoTime() + SPIN_NS;   // (bounded: the main thread's next frame usually comes soon)
            while (jobDone && !borrowAsked) {
                if (System.nanoTime() < spinUntil) Thread.onSpinWait();
                else if (EXTRA && (mainTicking || EXTRA_ANY && !mainRecording && System.nanoTime() - handAt < 1_000_000_000L) && frameHolds && nOps > 0) {
                    // (no sooner than the frame rate limit allows: extra frames are frames the player asked to cap; when
                    // the main thread is not known to be busy, only once it is half a frame late)
                    int cap = mc != null ? mc.getWindow().getFramerateLimit() : 260;
                    long iv = Math.max(lastFrameNs, cap > 0 && cap < 260 ? 1_000_000_000L / cap : 0);
                    long due = doneAt + (mainTicking ? iv : iv * 3 / 2) - System.nanoTime();
                    if (due > 0) LockSupport.parkNanos(due);
                    else if (extraFrame()) doneAt = System.nanoTime();
                } else LockSupport.park();
            }
            if (!jobDone) wakeNs += System.nanoTime() - handAt;
            if (jobDone && borrowAsked) {   // a borrow: release, wait until handed back (the next job)
                if (frameHolds) {
                    org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
                    org.lwjgl.opengl.GL.setCapabilities(null);
                    frameHolds = false;
                }
                LockSupport.unpark(mainThread);
                while (jobDone) LockSupport.park();
            }
            if (jobDone) continue;
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
                    long sf = sideFence;
                    if (sf != 0) org.lwjgl.opengl.GL32C.glWaitSync(sf, 0, org.lwjgl.opengl.GL32C.GL_TIMEOUT_IGNORED);
                    long q1 = System.nanoTime();
                    tSideWait += q1 - q0;
                    UploadRing.frameBegin();   // (runTick HEAD's on the main thread: GL - the frames-in-flight fence and wait)
                    com.mojang.blaze3d.systems.RenderSystem.replayQueue();
                    long q2 = System.nanoTime();
                    tReplay += q2 - q1;
                    int nCalls = JOB_CALLS.size();
                    for (int i = 0; i < JOB_CALLS.size(); i++) JOB_CALLS.get(i).call(JOB_CALL_ARGS.get(i));   // the main thread's renderer calls, in order
                    JOB_CALLS.clear();
                    JOB_CALL_ARGS.clear();
                    runRenderTasks();
                    long q3 = System.nanoTime();
                    tCalls += q3 - q2;
                    recordedAt = System.nanoTime();
                    recordedPt = realTimer.getGameTimeDeltaPartialTick(true);
                    runOps();
                    long dt = System.nanoTime() - q3;
                    tOps += dt;
                    lastFrameNs = dt;
                    long tot = System.nanoTime() - q0;
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
            jobDone = true;
            LockSupport.unpark(mainThread);
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

    /** main thread at a tick's start: the cursor and the turn factors now */
    private static void turnStart() {
        if (!EXTRA_TURN || !WINDOWS || mc == null || mc.player == null) {
            turnOk = false;
            return;
        }
        if (cursorMain == null) cursorMain = org.lwjgl.system.windows.POINT.calloc();
        org.lwjgl.system.windows.User32.GetCursorPos(cursorMain);
        tickCursorX = cursorMain.x();
        tickCursorY = cursorMain.y();
        double d2 = mc.options.sensitivity().get() * 0.6 + 0.2;
        turnScale = d2 * d2 * d2 * 8.0 * 0.15;
        turnOk = mc.mouseHandler.isMouseGrabbed() && !mc.options.smoothCamera && mc.options.getCameraType().isFirstPerson() && !mc.player.isScoping();
        if (mc.options.invertYMouse().get()) turnScale = -turnScale;   // (the sign of the pitch only: undone for yaw below)
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
        if (!active || !EXTRA_TURN || !MOUSE_ACC || !WINDOWS || !mainTicking || Thread.currentThread() != mainThread) return;
        long now = System.nanoTime();
        if (now - busySince < PUMP_AFTER_NS) return;   // (only in a long tick: in a normal one the next poll comes soon anyway)
        if (now - pumpAt < 1_000_000L) return;
        pumpAt = now;
        // a whole poll (the mouse's messages alone, taken with a filtered PeekMessage, made Windows mark the window as not
        // responding: 22 of 131 s watched with a 30-ms tick vs 0) - the input callbacks' work queued meanwhile
        // (FrameThreadPumpQueueMixin), GLFW's virtual cursor read after it for the extra frames' turn
        pumping = true;
        try {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
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
        return active && (!jobDone || extraRunning);
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

    public static void tickStart() {
        if (!mainTicking) busySince = System.nanoTime();
        mainTicking = true;
        if (turnFrame != frames) {   // (the queued tasks before it already took the turn's reference for this frame)
            turnStart();
            turnFrame = frames;
        }
        ticksSeen++;
        if (jobDone) ticksWhileIdle++;
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
        mainTicking = false;
        tickEndNs = System.nanoTime();
    }

    private static volatile boolean mainHolding;
    public static long mainHolds2;

    /**
     * the main thread is about to change what a drawing frame may read (TickAsync's join: its snapshots and pairs): no
     * frame or extra frame drawing from here until releaseForMain (it waits for the one drawing now)
     */
    public static void holdForMain() {
        if (!active || Thread.currentThread() != mainThread) return;
        mainHolding = true;
        mainHolds2++;
        awaitLast();
        if (extraRunning) waitPumping(() -> !extraRunning);
    }

    public static void releaseForMain() {
        if (!holdToFrame) mainHolding = false;   // (a level change's hold lasts until the next frame)
    }

    private static boolean holdToFrame;

    /**
     * a level change (FrameReactor's LEVEL_CHANGE: disconnect, a new level): no frame and no extra frame from the old
     * level until the main thread hands a new frame over (the extra frames' "main thread late" would draw it otherwise)
     */
    public static void holdUntilNextFrame() {
        if (!active || Thread.currentThread() != mainThread) return;
        holdForMain();
        holdToFrame = true;
    }

    /** a DeltaTracker whose partial tick goes on with the clock since the recorded frame (capped at 1) */
    private static final net.minecraft.client.DeltaTracker LIVE = new net.minecraft.client.DeltaTracker() {
        @Override public float getGameTimeDeltaTicks() { return 0f; }
        @Override public float getGameTimeDeltaPartialTick(boolean runsNormally) {
            return Math.min(1f, recordedPt + (System.nanoTime() - recordedAt) / 50_000_000f);
        }
        @Override public float getRealtimeDeltaTicks() { return 0f; }
    };

    private static Object[] live(Object[] args) {
        Object[] a = args.clone();
        net.minecraft.client.DeltaTracker t = PT_TICK_END ? TICK_END : LIVE;
        for (int i = 0; i < a.length; i++) if (a[i] instanceof net.minecraft.client.DeltaTracker) a[i] = t;
        return a;
    }

    /**
     * -Dlab.frameThread.ptFromTickEnd (non-exact, in lab.decoupled): every frame drawn on the frame thread takes its
     * partial tick from the END of the last client tick, not its start: (now - tick end) / ms per tick, capped at 1.
     * Vanilla's (from the start) reaches 1 as the next tick begins and stays there while it runs - with a long tick the
     * world stood still in the extra frames, then jumped (hand test 11-16, a 30-ms tick). From the end, the frames keep
     * interpolating the two known states while the next tick runs and reach 1 as it ends; the world is shown later by the
     * tick's own length (~1 ms in vanilla, 30 in the test). Every lerp by the partial tick follows (mods' entities too:
     * Minecraft.getTimer answers with it on the frame thread, FrameThreadTimerMixin).
     */
    public static final boolean PT_TICK_END = Boolean.getBoolean("lab.frameThread.ptFromTickEnd");
    private static volatile long tickEndNs;
    public static final net.minecraft.client.DeltaTracker TICK_END = new net.minecraft.client.DeltaTracker() {
        @Override public float getGameTimeDeltaTicks() { return realTimer.getGameTimeDeltaTicks(); }
        @Override public float getGameTimeDeltaPartialTick(boolean runsNormally) {
            net.minecraft.client.DeltaTracker base = realTimer;
            long end = tickEndNs;
            if (end == 0 || mc.isPaused() || !runsNormally && mc.level != null && mc.level.tickRateManager().isFrozen())
                return base.getGameTimeDeltaPartialTick(runsNormally);
            float ms = mc.level != null ? mc.level.tickRateManager().millisecondsPerTick() : 50f;
            return Math.max(0f, Math.min(1f, (System.nanoTime() - end) / (ms * 1_000_000f)));
        }
        @Override public float getRealtimeDeltaTicks() { return realTimer.getRealtimeDeltaTicks(); }
    };
    /** Minecraft's own timer, taken on the main thread at start (on the frame thread getTimer answers with TICK_END) */
    private static net.minecraft.client.DeltaTracker realTimer;

    /** the frame thread, idle: an extra frame if the main thread is in a tick and the last frame is older than one frame */
    private static boolean extraFrame() {
        if (!EXTRA || !OVERLAP || !(mainTicking || EXTRA_ANY) || mainRecording || borrowAsked || mainHolding || nOps == 0 || !jobDone) return false;
        extraRunning = true;
        if (!jobDone || borrowAsked || mainHolding || mainRecording) {   // (a job or a borrow came meanwhile)
            extraRunning = false;
            return false;
        }
        long t0 = System.nanoTime();
        drawing = true;
        extraNow = true;
        turnNow();
        try {
            UploadRing.frameBegin();
            runOps();
        } catch (Throwable t) {
            failure = t;
        } finally {
            extraNow = false;
            drawing = false;
        }
        extraFrames++;
        extraNs += System.nanoTime() - t0;
        extraRunning = false;
        LockSupport.unpark(mainThread);
        return true;
    }

    private static void runOps() {
        Minecraft m = mc;
        for (int k = 0; k < nOps; k++) {
            if (OPS[k] == 0) {   // a wrapped call: its Operation with the recorded arguments
                OPERATIONS[k].call(extraNow || PT_TICK_END ? live(ARGS[k]) : ARGS[k]);
                continue;
            }
            switch (OPS[k]) {
                case CLEAR -> com.mojang.blaze3d.systems.RenderSystem.clear(ARG_I[k], ARG_Z[k]);
                case BIND_WRITE -> m.getMainRenderTarget().bindWrite(ARG_Z[k]);
                case SETUP_NO_FOG -> net.minecraft.client.renderer.FogRenderer.setupNoFog();
                case ENABLE_CULL -> com.mojang.blaze3d.systems.RenderSystem.enableCull();
                case FRAME_PRE -> net.neoforged.neoforge.client.ClientHooks.fireRenderFramePre(PT_TICK_END ? TICK_END : realTimer);
                case RENDER -> m.gameRenderer.render(PT_TICK_END ? TICK_END : realTimer, ARG_Z[k]);
                case FRAME_POST -> net.neoforged.neoforge.client.ClientHooks.fireRenderFramePost(PT_TICK_END ? TICK_END : realTimer);
                case UNBIND_WRITE -> m.getMainRenderTarget().unbindWrite();
                case BLIT -> m.getMainRenderTarget().blitToScreen(ARG_I[k], ARG_J[k]);
                case UPDATE_DISPLAY -> m.getWindow().updateDisplay();
                default -> throw new IllegalStateException("frame op " + OPS[k]);
            }
        }
    }

    public static String report() {
        if (!ON) return "";
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "; frame thread: frames %d (%.3f ms a frame waited), overlapped %d (%.3f ms waited before the next), hand-over wake-ups: the frame thread %.1f us, the main thread %.1f us, borrows %d (%.3f ms each), failures %d",
                frames + extraFrames, waitNs / 1e6 / Math.max(1, frames), overlapFrames, overlapWaitNs / 1e6 / Math.max(1, overlapFrames), wakeNs / 1e3 / Math.max(1, frames),
                mainWakeNs / 1e3 / Math.max(1, mainWakes), borrows, borrowWaitNs / 1e6 / Math.max(1, borrows), failures));
        long fr = Math.max(1, frames);
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
