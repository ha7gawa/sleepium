package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Forge 1.20.1 draws the F3 text itself (ForgeGui.renderHUDText): a background fill and a string per line, each
 * flushed on its own. Here it is drawn inside GuiGraphics.drawManaged - one batch per type, drawn at its end, as
 * vanilla's own F3 does (DebugScreenOverlay.renderLines: every background, then every string; Forge's line-by-line
 * order let the next line's background cover the lowest row of a descender) - and with F3 open its vertices are kept
 * between refreshes (HudTextCache): no glyph layout every frame.
 */
@Pseudo
@Mixin(targets = "net.minecraftforge.client.gui.overlay.ForgeGui", remap = false)
public abstract class ForgeHudTextBatchMixin {
    @WrapMethod(method = "renderHUDText", remap = false)
    private void sleep$batched(int width, int height, GuiGraphics guiGraphics, Operation<Void> original) {
        dev.lull.optsupport.HudTextCache.render(guiGraphics, () -> original.call(width, height, guiGraphics),
                g -> original.call(width, height, g));
    }
}
