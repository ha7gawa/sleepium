package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The F3 screen's vertices kept between refreshes (HudTextCache): vanilla lays out its ~50 lines of text every frame;
 * here they are recorded every sleep.f3.refreshMs and replayed in between - no glyph layout each frame. With a chart
 * open it draws as vanilla.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenTextCacheMixin {
    @WrapMethod(method = "render")
    private void sleep$cached(GuiGraphics guiGraphics, Operation<Void> original) {
        dev.lull.optsupport.HudTextCache.render((DebugScreenOverlay) (Object) this, guiGraphics, () -> original.call(guiGraphics), g -> original.call(g));
    }
}
