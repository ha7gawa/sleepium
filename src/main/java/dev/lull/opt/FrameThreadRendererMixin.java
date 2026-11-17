package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread: no frame drawn while the main thread rebuilds the level renderer (allChanged: options, resource packs -
 * Embeddium takes its world renderer down and makes it again there) or gives it another level (setLevel, from
 * wherever it is called). Biohazard 11-17: an extra frame met Embeddium's section manager gone on leaving the world,
 * still with the level-engines hold (FrameThreadLevelMixin) in place.
 */
@Mixin(LevelRenderer.class)
public abstract class FrameThreadRendererMixin {
    @WrapMethod(method = "allChanged")
    private void sleep$allChangedHeld(Operation<Void> original) {
        FrameThread.holdForMain();
        try {
            original.call();
        } finally {
            FrameThread.releaseForMain();
        }
    }

    @WrapMethod(method = "setLevel")
    private void sleep$setLevelHeld(ClientLevel level, Operation<Void> original) {
        FrameThread.holdForMain();
        try {
            if (level == null) FrameThread.levelGone = true;
            original.call(level);
            if (level != null) FrameThread.levelGone = false;
        } finally {
            FrameThread.releaseForMain();
        }
    }
}
