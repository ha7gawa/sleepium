package dev.lull.sleepium.mixin;

import dev.lull.sleepium.gl.Capture;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The render cache: a Tessellator draw's vertices as they go out (into a recording, or into a display list being compiled). */
@Mixin(Tessellator.class)
public abstract class TessellatorCaptureMixin {
    @Shadow private int[] rawBuffer;
    @Shadow private int vertexCount;
    @Shadow private int drawMode;
    @Shadow private boolean hasTexture;
    @Shadow private boolean hasColor;
    @Shadow private boolean hasNormals;
    @Shadow private boolean hasBrightness;
    @Shadow private boolean isDrawing;

    @Inject(method = "draw", at = @At("HEAD"))
    private void sleep$capture(CallbackInfoReturnable<Integer> cir) {
        if (!isDrawing) return;
        Capture.inTessellator = true;
        if (vertexCount > 0) Capture.tess(rawBuffer, vertexCount, drawMode, hasTexture, hasColor, hasNormals, hasBrightness);
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void sleep$drawn(CallbackInfoReturnable<Integer> cir) {
        Capture.inTessellator = false;
    }
}
