package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.entity.Entity;

/**
 * -Dlab.opt.TickSlice (1.3.0 research, the client tick and the frame decoupled on one thread): when the client's tick
 * runs long, a frame is drawn in the middle of it - between two entities of ClientLevel.tickEntities - so slow logic
 * no longer stops the picture. The render thread stays the one GL thread (no GL from another thread, no races): the
 * tick is not run elsewhere, it is sliced and the frames come in between.
 *
 * <p>What a frame in the middle sees: the entities this tick already moved are drawn as usual (between their last two
 * positions); the ones it has not reached yet are drawn where they stand now (partialTick 1 - they wait a moment
 * instead of stepping back); particles and block entities, ticked after the entities, the same. The camera's entity
 * (the player) is ticked before any frame in the middle, so the view never jumps. The tick itself is vanilla's: the
 * same order, no packets or tasks between the slices (the queue is run before the tick, as vanilla does). Time the
 * frames in the middle saw is owed to the tick loop, not lost: the ticks still run 20 a second as in vanilla, and while
 * one is owed the frames show the last state (partialTick held near 1). -Dlab.tickSlice.ms (8): a frame at most this
 * long after the last one.
 */
public final class TickSlice {
    private TickSlice() {}

    public static final boolean ON = Boolean.getBoolean("lab.opt.TickSlice");
    /** -Dlab.tickSlice.ms: a fixed budget; unset - 1.5x the usual frame interval (an average of the frames), at least 2 ms */
    private static final long FIXED = (long) (Double.parseDouble(System.getProperty("lab.tickSlice.ms", "0")) * 1e6);
    /** -Dlab.tickSlice.factor: a frame in the middle once the frame would pass this many usual intervals (1.5) */
    private static final double FACTOR = Double.parseDouble(System.getProperty("lab.tickSlice.factor", "1.5"));
    private static long prevFrameEnd;
    private static double avgInterval = 8e6;
    /** the tick an entity was last ticked in (TickSliceEntityMixin: a field on the entity - entities tick on several
     *  threads with ParallelEntities, a shared set broke there: 11-14 a corrupted HashSet spun the render thread) */
    public interface Marked {
        int sleep$sliceRound();

        void sleep$setSliceRound(int r);
    }

    private static int round = 1;
    private static boolean inTick, inFrame, sliced, pinned;
    private static long lastFrameEnd;
    private static int owed;
    private static float savedResidual;
    public static long interimFrames, slicedTicks, ticks;

    /** the timer's residual (the partial tick) - DeltaTracker.Timer.deltaTickResidual */
    public interface TimerAccess {
        float sleep$residual();

        void sleep$setResidual(float r);
    }

    public static void frameEnd() {
        long now = System.nanoTime();
        if (prevFrameEnd != 0) {
            long iv = now - prevFrameEnd;
            if (iv < 100_000_000L) avgInterval += (iv - avgInterval) * 0.05;   // (a long pause - a menu, a load - not counted)
        }
        prevFrameEnd = lastFrameEnd = now;
    }

    private static long budget() {
        return FIXED > 0 ? FIXED : Math.max(2_000_000L, (long) (avgInterval * FACTOR));
    }

    public static void tickStart() {
        inTick = true;
        sliced = false;
        round++;
        ticks++;
    }

    public static void tickEnd() {
        inTick = false;
        if (sliced) slicedTicks++;
        sliced = false;
    }

    private static boolean tickedNow(Entity e) {
        return ((Marked) e).sleep$sliceRound() == round;
    }

    /** runTick's advanceTime: the ticks the frames in the middle owed are given back (none lost), the residual restored */
    public static int advance(DeltaTracker.Timer t, long time, boolean advanceGame) {
        if (pinned) {
            ((TimerAccess) t).sleep$setResidual(savedResidual);
            pinned = false;
        }
        int r = t.advanceTime(time, advanceGame);
        if (advanceGame) {
            r += owed;
            owed = 0;
        }
        return r;
    }

    /** a rider ticked by its vehicle */
    public static void ticked(Entity e) {
        if (inTick) ((Marked) e).sleep$setSliceRound(round);
    }

    /** ClientLevel.tickNonPassenger returned: a frame now, if the last one is a budget ago and the camera's entity has moved */
    public static void afterEntity(Entity e) {
        if (!inTick) return;
        ((Marked) e).sleep$setSliceRound(round);
        // a frame only on the render thread and never inside a parallel fan-out (ParallelEntities / ParallelParticles:
        // workers tick other entities at that moment - the frame must not run beside them)
        if (inFrame || ParticleWorkers.active || !RenderSystem.isOnRenderThread()) return;
        if (System.nanoTime() - lastFrameEnd < budget()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !tickedNow(mc.player) || mc.level == null) return;
        frame(mc);
    }

    /** an entity's partial tick in a frame in the middle of a tick */
    public static float partial(Entity e, float pt) {
        return inFrame && !tickedNow(e) ? 1.0f : pt;
    }

    /** particles and block entities: ticked after the entities, so not yet in a frame in the middle */
    public static float partialLater(float pt) {
        return inFrame ? 1.0f : pt;
    }

    public static boolean inFrame() {
        return inFrame;
    }

    private static void frame(Minecraft mc) {
        inFrame = true;
        sliced = true;
        interimFrames++;
        try {
            DeltaTracker.Timer timer = (DeltaTracker.Timer) mc.getTimer();
            TimerAccess a = (TimerAccess) timer;
            if (pinned) {
                a.sleep$setResidual(savedResidual);
                pinned = false;
            }
            owed += timer.advanceTime(Util.getMillis(), true);
            if (owed > 0) {   // the next tick is due already: the last state is shown until it has run
                savedResidual = a.sleep$residual();
                a.sleep$setResidual(0.9999f);
                pinned = true;
            }
            mc.getSoundManager().updateSource(mc.gameRenderer.getMainCamera());
            RenderSystem.clear(16640, Minecraft.ON_OSX);
            mc.getMainRenderTarget().bindWrite(true);
            FogRenderer.setupNoFog();
            RenderSystem.enableCull();
            mc.mouseHandler.handleAccumulatedMovement();
            if (!mc.noRender) {
                { }   // (Fabric: no frame events)
                mc.gameRenderer.render(timer, true);
                { }
            }
            mc.getMainRenderTarget().unbindWrite();
            mc.getMainRenderTarget().blitToScreen(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            mc.getWindow().updateDisplay();
        } finally {
            inFrame = false;
            lastFrameEnd = System.nanoTime();
        }
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "tick slice: %d ticks, %d sliced, %d frames drawn in the middle of a tick (budget %.2f ms)", ticks, slicedTicks, interimFrames, budget() / 1e6);
    }
}
