package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread: no frame drawn while the main thread puts a new level (or none) into the renderers - leaving a world and
 * changing levels both go through updateLevelInEngines, where Embeddium's world renderer is taken down and made again.
 * The level-change hold (FrameReactor LEVEL_CHANGE) ends at the next frame handed over, and 1.20.1's clearLevel hands
 * one over (its progress screen) before it gets here: Biohazard 11-17 crashed on leaving the world (an extra frame in
 * SodiumWorldRenderer.processChunkEvents with its section manager already gone). Here the frames in flight end first
 * and none begins until the renderers have the new level. Leaving a world, 1.20.1's clearLevel clears its level
 * field only after the renderers dropped it: no extra frame until a level comes back (FrameThread.levelGone).
 */
@Mixin(Minecraft.class)
public abstract class FrameThreadEnginesMixin {
    @WrapMethod(method = "updateLevelInEngines")
    private void sleep$levelHeld(ClientLevel level, Operation<Void> original) {
        FrameThread.holdForMain();
        try {
            if (level == null) FrameThread.levelGone = true;   // (before the renderers drop it: no extra frame from here on)
            original.call(level);
            if (level != null) FrameThread.levelGone = false;
        } finally {
            FrameThread.releaseForMain();
        }
    }
}
