package dev.lull.optsupport;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;

/**
 * -Dlab.opt.RenderThreadMixin=true (1.3.0 research, plan D, full decoupling - stage A1, inverted): the frame's drawing
 * (GameRenderer.render) runs on a thread of its own ("Sleepium render") that takes the GL context and the render
 * thread's role (RenderSystem.renderThread); the main thread keeps the game loop - the tick, the input, the window,
 * the task queue - so every check of the tick's thread (NeoForge's ModelDataManager owner, Minecraft's event loop,
 * GLFW's main-thread calls) holds as in vanilla. (Moving the tick instead, TickThread, met exactly those: the window
 * title as a cross-thread SendMessage, ModelDataManager's owning thread.) Stage A1 keeps vanilla's order - the main
 * thread waits for the frame, dispatching messages sent to the window meanwhile; A2 overlaps the next tick with it.
 */
public final class RenderThread {
    private RenderThread() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final boolean ON = Boolean.getBoolean("lab.opt.RenderThreadMixin");
    public static volatile boolean enabled = true;
    private static Thread thread, mainThread;
    private static volatile GameRenderer jobRenderer;
    private static DeltaTracker jobTracker;
    private static boolean jobLevel;
    private static volatile boolean done;
    private static volatile Throwable failure;
    private static org.lwjgl.opengl.GLCapabilities caps;
    private static long window;
    private static java.lang.reflect.Field renderThreadField;
    public static long frames, waitNs, switchNs, failures;

    public static long ownerViews;

    /** for an owning-thread check: the main thread while the render thread draws (stage A1: the main thread waits) */
    public static Thread ownerView() {
        Thread t = Thread.currentThread();
        if (drawing && t == thread) {
            ownerViews++;
            return mainThread;
        }
        return t;
    }

    /** this is the render thread, drawing a frame */
    public static boolean drawingHere() {
        return drawing && Thread.currentThread() == thread;
    }

    private static volatile boolean drawing;

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

    /** Minecraft.runTick's gameRenderer.render: on the render thread (enabled, in a world) or here */
    public static void render(GameRenderer gr, DeltaTracker tracker, boolean renderLevel) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!enabled || Thread.currentThread() == thread || mc.level == null || mc.player == null) {
            gr.render(tracker, renderLevel);
            return;
        }
        if (thread == null) {
            mainThread = Thread.currentThread();
            caps = org.lwjgl.opengl.GL.getCapabilities();
            window = mc.getWindow().getWindow();
            thread = new Thread(RenderThread::loop, "Sleepium render");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            LOG.info("[sleep/opt] render thread: the frame drawn on a thread of its own (stage A1: the main thread waits)");
        }
        long t0 = System.nanoTime();
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
        org.lwjgl.opengl.GL.setCapabilities(null);
        setRenderThread(thread);
        switchNs += System.nanoTime() - t0;
        jobTracker = tracker;
        jobLevel = renderLevel;
        done = false;
        jobRenderer = gr;
        LockSupport.unpark(thread);
        waitPumping();
        long s0 = System.nanoTime();
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.setCapabilities(caps);
        setRenderThread(mainThread);
        switchNs += System.nanoTime() - s0;
        waitNs += System.nanoTime() - t0;
        frames++;
        Throwable f = failure;
        if (f != null) {
            failure = null;
            failures++;
            if (f instanceof RuntimeException r) throw r;
            if (f instanceof Error e) throw e;
            throw new RuntimeException(f);
        }
    }

    private static final boolean WINDOWS = org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS;
    private static org.lwjgl.system.windows.MSG msg;

    /** the main thread waits, dispatching messages sent to the window (posted input stays queued for the next poll) */
    private static void waitPumping() {
        if (!WINDOWS) {
            while (!done) LockSupport.park();
            return;
        }
        if (msg == null) msg = org.lwjgl.system.windows.MSG.calloc();
        long spinUntil = System.nanoTime() + 20_000;
        while (!done) {
            org.lwjgl.system.windows.User32.PeekMessage(msg, 0L, 0, 0, org.lwjgl.system.windows.User32.PM_NOREMOVE | 0x00400000 /* PM_QS_SENDMESSAGE */);
            if (done) break;
            if (System.nanoTime() < spinUntil) Thread.onSpinWait();
            else LockSupport.parkNanos(100_000L);
        }
    }

    private static void loop() {
        while (true) {
            GameRenderer gr;
            while ((gr = jobRenderer) == null) LockSupport.park();
            jobRenderer = null;
            try {
                org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
                org.lwjgl.opengl.GL.setCapabilities(caps);
                drawing = true;
                try {
                    gr.render(jobTracker, jobLevel);
                } finally {
                    drawing = false;
                    org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
                    org.lwjgl.opengl.GL.setCapabilities(null);
                }
            } catch (Throwable t) {
                failure = t;
            }
            done = true;
            LockSupport.unpark(mainThread);
        }
    }

    public static String report() {
        return ON ? String.format(java.util.Locale.ROOT, "; render thread: frames %d (%.3f ms a frame handed over, context switches %.3f ms a frame), failures %d",
                frames, waitNs / 1e6 / Math.max(1, frames), switchNs / 1e6 / Math.max(1, frames), failures) : "";
    }
}
