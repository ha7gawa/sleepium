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

    private static void begin() {
        awaitLast();
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
        org.lwjgl.glfw.GLFW.glfwPollEvents();
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

    private static boolean sideWanted() {
        Boolean w = sideWanted;
        if (w == null) {
            int mods;
            try {
                mods = net.neoforged.fml.ModList.get().size();
            } catch (Throwable t) {
                mods = 99;
            }
            w = "true".equals(SIDE) || "auto".equals(SIDE) && mods > 3;   // (minecraft, neoforge, sleepium)
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
                else if (EXTRA && mainTicking && frameHolds && nOps > 0) {
                    // (no sooner than the frame rate limit allows: extra frames are frames the player asked to cap)
                    int cap = mc != null ? mc.getWindow().getFramerateLimit() : 260;
                    long due = doneAt + Math.max(lastFrameNs, cap > 0 && cap < 260 ? 1_000_000_000L / cap : 0) - System.nanoTime();
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
                    long sf = sideFence;
                    if (sf != 0) org.lwjgl.opengl.GL32C.glWaitSync(sf, 0, org.lwjgl.opengl.GL32C.GL_TIMEOUT_IGNORED);
                    long q1 = System.nanoTime();
                    tSideWait += q1 - q0;
                    UploadRing.frameBegin();   // (runTick HEAD's on the main thread: GL - the frames-in-flight fence and wait)
                    com.mojang.blaze3d.systems.RenderSystem.replayQueue();
                    long q2 = System.nanoTime();
                    tReplay += q2 - q1;
                    for (int i = 0; i < JOB_CALLS.size(); i++) JOB_CALLS.get(i).call(JOB_CALL_ARGS.get(i));   // the main thread's renderer calls, in order
                    JOB_CALLS.clear();
                    JOB_CALL_ARGS.clear();
                    long q3 = System.nanoTime();
                    tCalls += q3 - q2;
                    recordedAt = System.nanoTime();
                    recordedPt = mc.getTimer().getGameTimeDeltaPartialTick(true);
                    runOps();
                    long dt = System.nanoTime() - q3;
                    tOps += dt;
                    lastFrameNs = dt;
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
        if (cursorFrame == null) cursorFrame = org.lwjgl.system.windows.POINT.calloc();
        org.lwjgl.system.windows.User32.GetCursorPos(cursorFrame);
        double k = Math.abs(turnScale);
        turnYaw = (float) ((cursorFrame.x() - tickCursorX) * k);
        turnPitch = (float) ((cursorFrame.y() - tickCursorY) * turnScale);
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
        if (!EXTRA_TASKS || !EXTRA) return;
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
        while (System.nanoTime() < until) Thread.onSpinWait();
    }

    public static void tickEnd() {
        mainTicking = false;
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
        mainHolding = false;
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
        for (int i = 0; i < a.length; i++) if (a[i] instanceof net.minecraft.client.DeltaTracker) a[i] = LIVE;
        return a;
    }

    /** the frame thread, idle: an extra frame if the main thread is in a tick and the last frame is older than one frame */
    private static boolean extraFrame() {
        if (!EXTRA || !OVERLAP || !mainTicking || borrowAsked || mainHolding || nOps == 0 || !jobDone) return false;
        extraRunning = true;
        if (!jobDone || borrowAsked || mainHolding) {   // (a job or a borrow came meanwhile)
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
                OPERATIONS[k].call(extraNow ? live(ARGS[k]) : ARGS[k]);
                continue;
            }
            switch (OPS[k]) {
                case CLEAR -> com.mojang.blaze3d.systems.RenderSystem.clear(ARG_I[k], ARG_Z[k]);
                case BIND_WRITE -> m.getMainRenderTarget().bindWrite(ARG_Z[k]);
                case SETUP_NO_FOG -> net.minecraft.client.renderer.FogRenderer.setupNoFog();
                case ENABLE_CULL -> com.mojang.blaze3d.systems.RenderSystem.enableCull();
                case FRAME_PRE -> net.neoforged.neoforge.client.ClientHooks.fireRenderFramePre(m.getTimer());
                case RENDER -> m.gameRenderer.render(m.getTimer(), ARG_Z[k]);
                case FRAME_POST -> net.neoforged.neoforge.client.ClientHooks.fireRenderFramePost(m.getTimer());
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
        BORROW_SITES.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(8)
                .forEach(e -> b.append("; borrow ").append(e.getValue()[0]).append(" x ").append(e.getKey()));
        return b.toString();
    }
}
