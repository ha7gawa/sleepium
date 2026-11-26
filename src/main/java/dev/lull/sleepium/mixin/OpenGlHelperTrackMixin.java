package dev.lull.sleepium.mixin;

import dev.lull.sleepium.gl.Capture;
import dev.lull.sleepium.gl.GL;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sleepium's GL: the state OpenGlHelper sets with GL13 / GL14 / GL20 (the active texture unit, a separate blend function, shader programs). */
@Mixin(OpenGlHelper.class)
public abstract class OpenGlHelperTrackMixin {
    @Inject(method = "setActiveTexture", at = @At("HEAD"))
    private static void sleep$unit(int unit, CallbackInfo ci) {
        GL.activeTexture(unit);
    }

    @Inject(method = "glBlendFunc", at = @At("HEAD"))
    private static void sleep$blend(int src, int dst, int srcA, int dstA, CallbackInfo ci) {
        GL.blendSeparate(src, dst, srcA, dstA);
    }

    @Inject(method = "func_153161_d", at = @At("HEAD"))
    private static void sleep$program(int program, CallbackInfo ci) {
        if (program != 0) Capture.unsupported("shader program");
    }
}
