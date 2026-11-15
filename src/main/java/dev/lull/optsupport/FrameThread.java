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
    public static boolean recording() {
        if (!ON) return false;
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
        // the context goes to the frame thread (it takes it at its first job)
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
        org.lwjgl.opengl.GL.setCapabilities(null);
        setRenderThread(thread);
        active = true;
        thread.start();
        LOG.info("[sleep/opt] frame thread: the GL context and the frame's drawing on a thread of their own (stage A1b)");
    }

    public static void op(int code) {
        OPS[nOps++] = code;
    }

    public static void op(int code, int i, int j, boolean z) {
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
    public static void present() {
        org.lwjgl.glfw.GLFW.glfwPollEvents();
        if (mainHolds) giveBack();
        long t0 = System.nanoTime();
        jobDone = false;
        LockSupport.unpark(thread);
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
            String site = st.length > 3 ? st[2] + " <- " + st[3] : "?";
            BORROW_SITES.computeIfAbsent(site, k -> new long[1])[0]++;
        }
        borrowAsked = true;
        LockSupport.unpark(thread);
        waitPumping(() -> !frameHolds && jobDone);
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.setCapabilities(caps);
        setRenderThread(mainThread);
        mainHolds = true;
        borrows++;
        borrowWaitNs += System.nanoTime() - t0;
    }

    private static final boolean BORROW_LOG = !"false".equals(System.getProperty("lab.frameThread.borrowLog"));

    private static void giveBack() {
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
        org.lwjgl.opengl.GL.setCapabilities(null);
        setRenderThread(thread);
        mainHolds = false;
        borrowAsked = false;
    }

    private static final boolean WINDOWS = org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS;
    private static org.lwjgl.system.windows.MSG msg;

    private static void waitPumping(java.util.function.BooleanSupplier until) {
        if (!WINDOWS) {
            while (!until.getAsBoolean()) LockSupport.parkNanos(100_000L);
            return;
        }
        if (msg == null) msg = org.lwjgl.system.windows.MSG.calloc();
        long spinUntil = System.nanoTime() + 20_000;
        while (!until.getAsBoolean()) {
            org.lwjgl.system.windows.User32.PeekMessage(msg, 0L, 0, 0, org.lwjgl.system.windows.User32.PM_NOREMOVE | 0x00400000 /* PM_QS_SENDMESSAGE */);
            if (until.getAsBoolean()) break;
            if (System.nanoTime() < spinUntil) Thread.onSpinWait();
            else LockSupport.parkNanos(100_000L);
        }
    }

    // ------------------------------------------------------------------ the frame thread

    private static void loop() {
        while (true) {
            while (jobDone && !borrowAsked) LockSupport.park();
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
                    UploadRing.frameBegin();   // (runTick HEAD's on the main thread: GL - the frames-in-flight fence and wait)
                    com.mojang.blaze3d.systems.RenderSystem.replayQueue();
                    runOps();
                } finally {
                    drawing = false;
                }
            } catch (Throwable t) {
                failure = t;
            }
            nOps = 0;
            jobDone = true;
            LockSupport.unpark(mainThread);
        }
    }

    private static void runOps() {
        Minecraft m = mc;
        for (int k = 0; k < nOps; k++) {
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
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "; frame thread: frames %d (%.3f ms a frame waited), borrows %d (%.3f ms each), failures %d",
                frames, waitNs / 1e6 / Math.max(1, frames), borrows, borrowWaitNs / 1e6 / Math.max(1, borrows), failures));
        BORROW_SITES.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(8)
                .forEach(e -> b.append("; borrow ").append(e.getValue()[0]).append(" x ").append(e.getKey()));
        return b.toString();
    }
}
