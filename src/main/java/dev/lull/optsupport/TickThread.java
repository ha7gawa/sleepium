package dev.lull.optsupport;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;

/**
 * -Dlab.opt.TickThreadMixin=true (1.3.0 research, plan D, full decoupling - stage A1): Minecraft.tick runs on a thread
 * of its own ("Sleepium client tick") instead of the render thread. Stage A1 keeps vanilla's order exactly - the render
 * thread hands the tick over and waits for it - and finds what breaks when the tick and the mods' tick code run off the
 * render thread: the tick has no GL context, so its GL work comes back to the render thread (the animated textures'
 * tick runs there right after the tick; vanilla's own off-thread paths queue through RenderSystem.recordRenderCall);
 * Minecraft's event loop counts the tick thread as its own while the tick runs (getRunningThread), so tasks the tick
 * hands to Minecraft.execute run at once as on the render thread. Stage A2: frames drawn while the tick runs.
 */
public final class TickThread {
    private TickThread() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final boolean ON = Boolean.getBoolean("lab.opt.TickThreadMixin");
    /** off: the tick on the render thread again (ClientAuto's flip) */
    public static volatile boolean enabled = true;
    private static Thread thread, renderThread;
    private static volatile Minecraft job;
    private static volatile boolean done;
    private static volatile Throwable failure;
    /** the tick thread is running a tick (the render thread waits) */
    public static volatile boolean running;
    /** the texture tick was asked inside the tick: run on the render thread after it */
    private static boolean texturesDue;
    public static long ticks, waitNs, textureTicks, failures, switchNs;
    /**
     * -Dlab.tickThread.context (default true): the GL context and the render thread's role (RenderSystem.renderThread)
     * go to the tick thread for the tick - every RenderSystem / GL call of the tick and the mods' tick code works as on
     * the render thread (stage A1 only: the render thread waits meanwhile); false: no context there (GL work deferred)
     */
    private static final boolean CONTEXT = !"false".equals(System.getProperty("lab.tickThread.context"));
    private static org.lwjgl.opengl.GLCapabilities caps;
    private static long window;
    private static java.lang.reflect.Field renderThreadField;

    private static void setRenderThread(Thread t) {
        try {
            if (renderThreadField == null) {
                renderThreadField = dev.lull.Names.fieldOf(com.mojang.blaze3d.systems.RenderSystem.class, "renderThread");
                renderThreadField.setAccessible(true);
            }
            renderThreadField.set(null, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean onTickThread() {
        return running && Thread.currentThread() == thread;
    }

    /** the tick thread, while it runs a tick (Minecraft.getRunningThread), else null */
    public static Thread runningThread() {
        return running ? thread : null;
    }

    /** Minecraft.runTick: one client tick - on the tick thread (enabled) or here */
    public static void tick(Minecraft mc) {
        // vanilla's way: off, nested (a world load / disconnect inside the tick runs runTick on the tick thread), or no world
        if (!enabled || Thread.currentThread() == thread || mc.level == null || mc.player == null) {
            mc.tick();
            return;
        }
        if (thread == null) {
            renderThread = Thread.currentThread();
            thread = new Thread(TickThread::loop, "Sleepium client tick");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
            LOG.info("[sleep/opt] tick thread: the client tick on a thread of its own (stage A1: the render thread waits)");
        }
        long t0 = System.nanoTime();
        if (CONTEXT) {   // the context and the render thread's role to the tick thread (it takes them in loop)
            if (caps == null) {
                caps = org.lwjgl.opengl.GL.getCapabilities();
                window = mc.getWindow().getWindow();
            }
            long s0 = System.nanoTime();
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
            org.lwjgl.opengl.GL.setCapabilities(null);
            setRenderThread(thread);
            switchNs += System.nanoTime() - s0;
        }
        done = false;
        running = true;
        job = mc;
        LockSupport.unpark(thread);
        waitPumping();
        running = false;
        if (CONTEXT) {
            long s0 = System.nanoTime();
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            org.lwjgl.opengl.GL.setCapabilities(caps);
            setRenderThread(renderThread);
            switchNs += System.nanoTime() - s0;
        }
        waitNs += System.nanoTime() - t0;
        ticks++;
        if (texturesDue) {   // the texture tick's GL work, here (before the frame, as vanilla has it)
            texturesDue = false;
            textureTicks++;
            mc.getTextureManager().tick();
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

    private static final boolean WINDOWS = org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS;
    private static org.lwjgl.system.windows.MSG msg;
    public static long pumps;

    /**
     * the render thread waits for the tick - on Windows dispatching the messages other threads send to the game window
     * meanwhile (the tick's glfwSetWindowTitle / input-mode calls are SendMessage to this thread: a plain park was a
     * deadlock); posted messages - the input - stay queued for the next frame's poll, as vanilla has them
     */
    private static void waitPumping() {
        if (!WINDOWS) {
            while (!done) LockSupport.park();
            return;
        }
        if (msg == null) msg = org.lwjgl.system.windows.MSG.calloc();
        while (!done) {
            org.lwjgl.system.windows.User32.PeekMessage(msg, 0L, 0, 0, org.lwjgl.system.windows.User32.PM_NOREMOVE | 0x00400000 /* PM_QS_SENDMESSAGE */);
            pumps++;
            if (!done) LockSupport.parkNanos(100_000L);
        }
    }

    /** Minecraft.tick's textureManager.tick() (TickThreadMixin): on the tick thread only noted */
    public static boolean deferTextures() {
        if (CONTEXT || !onTickThread()) return false;   // (with the context the tick does its own GL)
        texturesDue = true;
        return true;
    }

    private static void loop() {
        while (true) {
            Minecraft mc;
            while ((mc = job) == null) LockSupport.park();
            job = null;
            try {
                if (CONTEXT) {
                    org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
                    org.lwjgl.opengl.GL.setCapabilities(caps);
                }
                try {
                    mc.tick();
                } finally {
                    if (CONTEXT) {
                        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(0L);
                        org.lwjgl.opengl.GL.setCapabilities(null);
                    }
                }
            } catch (Throwable t) {
                failure = t;
            }
            done = true;
            LockSupport.unpark(renderThread);
        }
    }

    public static String report() {
        return ON ? String.format(java.util.Locale.ROOT, "; tick thread: ticks %d (%.3f ms a tick handed over, context switches %.3f ms a tick), texture ticks after %d, failures %d",
                ticks, waitNs / 1e6 / Math.max(1, ticks), switchNs / 1e6 / Math.max(1, ticks), textureTicks, failures) : "";
    }
}
