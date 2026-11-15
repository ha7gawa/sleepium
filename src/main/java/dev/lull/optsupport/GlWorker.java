package dev.lull.optsupport;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * -Dlab.glWorker=true (1.3.0 research, plan D - GL work off the render thread): a thread with a GL context of its own,
 * shared with the game window's (a hidden 1x1 GLFW window), that runs GL work the render thread records - first the
 * animated textures' tick (GpuAnim: the frame copies and the batched upload of the interpolated frames, ~0.5-0.8 ms of
 * the N100's client tick) - while the render thread goes on with the rest of the tick.
 *
 * <p>Order, as vanilla's one context has it: a job first waits on the GPU (glWaitSync) for the fence put after the last
 * frame's draws (frameEnd, before the swap that sends it), so no frame still queued reads an atlas region the job
 * writes; the render thread, before the next frame's draws (joinIfBusy), waits on the GPU for the job's own fence. A job
 * that needs objects the render thread just made (a new sheet texture, the ring's buffer) gets a render-side fence with a
 * flush instead. The CPU waits are short parks (no spinning past ~20 us). One job at a time.
 */
public final class GlWorker {
    private GlWorker() {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sleep");
    public static final boolean ON = Boolean.getBoolean("lab.glWorker");
    /** off: the work done on the render thread again (ClientAuto's in-window flip) */
    public static volatile boolean enabled = true;
    /** -Dlab.glWorker.lateJoin=true: the job waited for at the frame's first terrain layer (the first draws reading an atlas) */
    public static final boolean LATE_JOIN = Boolean.getBoolean("lab.glWorker.lateJoin");
    private static boolean started, broken;
    private static long window;
    private static Thread worker, renderThread;
    private static volatile boolean ready;
    private static volatile String failure;

    // ------------------------------------------------------------------ the job (one at a time)

    /** 0 idle, 1 handed over, 2 done (doneFence published) */
    private static volatile int state;
    private static Runnable job;
    private static LongConsumer onJoin;
    private static long waitFor, doneFence;
    private static long renderFence;
    private static final long[] FRAME_FENCES = new long[4];
    private static int frameSlot;
    private static long lastFrameFence;
    public static long jobs, joins, joinWaits, joinWaitNs, renderSyncs, workNs;

    /** render thread: work may be handed over now (the context made on first use) */
    public static boolean usable() {
        if (!ON || !enabled || broken) return false;
        if (!started) start();
        return ready && !broken;
    }

    public static boolean busy() {
        return state != 0;
    }

    private static void start() {
        started = true;
        try {
            long main = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(1, 1, "sleepium gl worker", 0L, main);
            GLFW.glfwDefaultWindowHints();
            if (window == 0) {
                fail("no shared window could be made");
                return;
            }
            renderThread = Thread.currentThread();
            worker = new Thread(GlWorker::run, "Sleepium GL worker");
            worker.setDaemon(true);
            worker.start();
            long until = System.nanoTime() + 3_000_000_000L;
            while (!ready && failure == null && System.nanoTime() < until) LockSupport.parkNanos(200_000L);
            if (!ready) fail(failure != null ? failure : "the worker's context did not start");
            else LOG.info("[sleep/opt] gl worker: a shared context on its own thread ({})", GL11C.glGetString(GL11C.GL_VERSION));
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    private static void fail(String why) {
        broken = true;
        LOG.warn("[sleep/opt] gl worker: off - {}", why);
    }

    private static void run() {
        try {
            GLFW.glfwMakeContextCurrent(window);
            GLCapabilities caps = GL.createCapabilities();
            if (!(caps.OpenGL45 || caps.GL_ARB_direct_state_access && caps.GL_ARB_copy_image)) {
                failure = "the shared context lacks GL 4.5 / direct state access + copy_image";
                return;
            }
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
            ready = true;
        } catch (Throwable t) {
            failure = t.toString();
            return;
        }
        while (true) {
            while (state != 1) LockSupport.park();
            long t0 = System.nanoTime();
            long f = 0;
            try {
                if (waitFor != 0) GL32C.glWaitSync(waitFor, 0, GL32C.GL_TIMEOUT_IGNORED);
                job.run();
                f = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                GL11C.glFlush();   // (the render context waits on it: it must have been sent)
            } catch (Throwable t) {
                failure = t.toString();
                broken = true;
            }
            workNs += System.nanoTime() - t0;
            doneFence = f;
            state = 2;
            LockSupport.unpark(renderThread);
        }
    }

    /**
     * render thread: the job handed over; {@code renderSync}: it uses objects made on the render context since the last
     * frame (a fence with a flush here first). {@code joined} gets the job's fence on the render thread (owned by it).
     */
    public static void submit(Runnable r, boolean renderSync, LongConsumer joined) {
        if (state != 0) joinIfBusy();
        if (renderSync || lastFrameFence == 0) {
            if (renderFence != 0) GL32C.glDeleteSync(renderFence);
            renderFence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11C.glFlush();
            waitFor = renderFence;
            renderSyncs++;
        } else {
            waitFor = lastFrameFence;
        }
        job = r;
        onJoin = joined;
        jobs++;
        state = 1;
        LockSupport.unpark(worker);
    }

    /** render thread: the job (if any) done - its fence waited for on the GPU, then handed to the job's owner */
    public static void joinIfBusy() {
        if (state == 0) return;
        long t0 = System.nanoTime();
        if (state == 1) {
            joinWaits++;
            long spinUntil = t0 + 20_000;
            while (state == 1) {
                if (System.nanoTime() < spinUntil) Thread.onSpinWait();
                else LockSupport.parkNanos(50_000L);
            }
        }
        long f = doneFence;
        doneFence = 0;
        state = 0;
        if (f != 0) GL32C.glWaitSync(f, 0, GL32C.GL_TIMEOUT_IGNORED);
        joins++;
        joinWaitNs += System.nanoTime() - t0;
        if (broken && failure != null) {
            LOG.warn("[sleep/opt] gl worker: a job failed ({}) - the work goes back to the render thread", failure);
            failure = null;
        }
        LongConsumer c = onJoin;
        onJoin = null;
        job = null;
        if (c != null) c.accept(f);
        else if (f != 0) GL32C.glDeleteSync(f);
    }

    /** RenderSystem.flipFrame HEAD: a fence after the frame's draws (the swap sends it) - the next job waits for it on the GPU */
    public static void frameEnd() {
        if (!started || broken) return;
        joinIfBusy();
        int i = frameSlot = (frameSlot + 1) & 3;
        if (FRAME_FENCES[i] != 0) GL32C.glDeleteSync(FRAME_FENCES[i]);
        lastFrameFence = FRAME_FENCES[i] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public static String report() {
        if (!ON) return "";
        return String.format(java.util.Locale.ROOT, "; gl worker: %s, jobs %d (render-side syncs %d), %.3f ms a job on the worker, joins %d (%d waited for, %.3f ms a join)",
                broken ? "off" : ready ? "on" : "not started", jobs, renderSyncs, workNs / 1e6 / Math.max(1, jobs), joins, joinWaits, joinWaitNs / 1e6 / Math.max(1, joins));
    }
}
