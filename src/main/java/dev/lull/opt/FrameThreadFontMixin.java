package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread: a font's glyph caches (FontSet: maps filled on first use, a glyph baked into the font texture there) are
 * not made for two threads - text drawn by the main thread (a screen's tick) and by the frame thread at once baked the
 * same glyph twice, one closing the image the other was uploading ("Image is not allocated", 1.21.1 nether trip with
 * transitions, 11-17). The lookups under the font's lock while the frame thread runs (uncontended: a few ns each); the
 * main thread borrows the context first when it may bake (getGlyph), never under the lock.
 */
@Mixin(FontSet.class)
public abstract class FrameThreadFontMixin {
    @WrapMethod(method = "getGlyph")
    private net.minecraft.client.gui.font.glyphs.BakedGlyph sleep$glyph(int codepoint, Operation<net.minecraft.client.gui.font.glyphs.BakedGlyph> original) {
        if (!FrameThread.active) return original.call(codepoint);
        // (the main thread: the context borrowed BEFORE the lock - a glyph baked here uploads to the font texture, and a
        // borrow taken under the lock waited for a frame thread that waited for the lock: a hang, 11-17)
        if (FrameThread.onMainThread()) FrameThread.borrowIfNeeded();
        synchronized (this) {
            return original.call(codepoint);
        }
    }

    @WrapMethod(method = "getGlyphInfo")
    private com.mojang.blaze3d.font.GlyphInfo sleep$glyphInfo(int codepoint, boolean filterFishy, Operation<com.mojang.blaze3d.font.GlyphInfo> original) {
        if (!FrameThread.active) return original.call(codepoint, filterFishy);
        synchronized (this) {
            return original.call(codepoint, filterFishy);
        }
    }
}
