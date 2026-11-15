package dev.lull.opt;

import dev.lull.optsupport.ClientLightAsync;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/client-light-async (client): renderLevel's light updates on a worker that may span several frames. At the light
 * point: a job still running with no queued light tasks is left running (nothing else adds light work between ticks and
 * packets), otherwise it is joined first. The end of renderLevel and a level change are FrameReactor points (a finished
 * job's section marks come back through its completion ring); ticks and packets are points too (ClientLightJoinMixin).
 */
@Mixin(LevelRenderer.class)
public abstract class ClientLightAsyncMixin {
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"))
    private void sleep$poll(ClientLevel level) {
        if (dev.lull.optsupport.FrameThread.lightOnMain()) return;   // (a frame drawn while the main thread ticks: the main thread did this point before handing it over)
        lightPoll(level);
    }

    /** the light point's poll (renderLevel's, or the main thread's before a frame it hands over: FrameThread) */
    private static void lightPoll(ClientLevel level) {
        if (ClientLightAsync.running() && ((ClientLevelLightQueueAccessor) level).sleep$lightQueue().isEmpty()) {
            ClientLightAsync.skipNext = true;   // the running job has all the work there is
            ClientLightAsync.skipped++;
            return;
        }
        ClientLightAsync.join();
        ClientLightAsync.skipNext = false;
        level.pollLightUpdates();
    }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private int sleep$async(LevelLightEngine engine) {
        if (dev.lull.optsupport.FrameThread.lightOnMain()) return 0;
        return lightRun(engine);
    }

    private static int lightRun(LevelLightEngine engine) {
        if (ClientLightAsync.skipNext) {
            ClientLightAsync.skipNext = false;
            return 0;
        }
        ClientLightAsync.start(engine);
        return 0;   // vanilla ignores the count
    }

    /** FrameThread (overlap): the light point on the main thread, before the frame is handed over */
    @org.spongepowered.asm.mixin.Unique
    private static void sleep$lightPointOnMain(ClientLevel level) {
        lightPoll(level);
        lightRun(level.getChunkSource().getLightEngine());
    }

    static {
        dev.lull.optsupport.FrameThread.lightPoint = ClientLightAsyncMixin::sleep$lightPointOnMain;
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void sleep$endOfLevel(CallbackInfo ci) {
        dev.lull.optsupport.FrameReactor.reach(dev.lull.optsupport.FrameReactor.Point.END_OF_LEVEL);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void sleep$levelChange(CallbackInfo ci) {
        dev.lull.optsupport.FrameReactor.reach(dev.lull.optsupport.FrameReactor.Point.LEVEL_CHANGE);
    }
}
