package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * FrameThread: renderLevel's light point (pollLightUpdates, runLightUpdates) skipped on the frame thread - the main thread
 * ran it before the hand-over (FrameThread.present). ClientLightAsyncMixin skips it too, but it yields when another mod
 * adds the same method name (Biohazard, 11-17): the frame thread then ran the light engine while the main thread did,
 * and its section set broke (an NPE in LayerLightSectionStorage's iterator). Wrapped calls: other mods' wrappers stay.
 */
@Mixin(LevelRenderer.class)
public abstract class FrameThreadLightSkipMixin {
    @WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"))
    private void sleep$pollOnMain(ClientLevel level, Operation<Void> op) {
        if (!FrameThread.lightOnMain()) op.call(level);
    }

    @WrapOperation(method = "renderLevel", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private int sleep$runOnMain(LevelLightEngine engine, Operation<Integer> op) {
        return FrameThread.lightOnMain() ? 0 : op.call(engine);
    }
}
