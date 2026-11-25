package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.EntityCache;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EntityCache's capture: immediate-mode glyphs, and the GL calls a recording cannot represent. */
@Mixin(GlStateManager.class)
public abstract class EntityCacheGlMixin {
    @Inject(method = "glBegin", at = @At("HEAD"))
    private static void sleep$begin(int mode, CallbackInfo ci) {
        EntityCache.imBegin(mode);
    }

    @Inject(method = "glTexCoord2f", at = @At("HEAD"))
    private static void sleep$tex(float u, float v, CallbackInfo ci) {
        EntityCache.imTex(u, v);
    }

    @Inject(method = "glVertex3f", at = @At("HEAD"))
    private static void sleep$vertex(float x, float y, float z, CallbackInfo ci) {
        EntityCache.imVertex(x, y, z);
    }

    @Inject(method = "glEnd", at = @At("HEAD"))
    private static void sleep$end(CallbackInfo ci) {
        EntityCache.imEnd();
    }

    @Inject(method = "callList", at = @At("HEAD"))
    private static void sleep$list(int list, CallbackInfo ci) {
        if (!EntityCache.inPart()) EntityCache.unsupported("display list");
    }

    @Inject(method = "matrixMode", at = @At("HEAD"))
    private static void sleep$mode(int mode, CallbackInfo ci) {
        if (mode != 5888) EntityCache.unsupported("matrix mode " + mode);
    }

    @Inject(method = {"glTexEnv", "glTexEnvi", "glTexEnvf"}, at = @At("HEAD"))
    private static void sleep$texEnv(CallbackInfo ci) {
        EntityCache.unsupported("texture environment");
    }

    @Inject(method = {"enableTexGenCoord", "texGen"}, at = @At("HEAD"))
    private static void sleep$texGen(CallbackInfo ci) {
        EntityCache.unsupported("texgen");
    }

    @Inject(method = "pushAttrib", at = @At("HEAD"))
    private static void sleep$push(CallbackInfo ci) {
        if (EntityCache.ON) net.minecraft.client.renderer.SleepGlState.onPushAttrib();
    }

    @Inject(method = "popAttrib", at = @At("HEAD"))
    private static void sleep$pop(CallbackInfo ci) {
        if (EntityCache.ON) net.minecraft.client.renderer.SleepGlState.onPopAttrib();
    }

    @Inject(method = "glTexParameteri", at = @At("HEAD"))
    private static void sleep$texParamI(int target, int pname, int value, CallbackInfo ci) {
        if (EntityCache.ON) net.minecraft.client.renderer.SleepGlState.onTexParameter(target, pname, value);
    }

    @Inject(method = "glTexParameterf", at = @At("HEAD"))
    private static void sleep$texParamF(int target, int pname, float value, CallbackInfo ci) {
        if (EntityCache.ON) net.minecraft.client.renderer.SleepGlState.onTexParameter(target, pname, (int) value);
    }

    @Inject(method = "color(FFFF)V", at = @At("HEAD"))
    private static void sleep$color(float r, float g, float b, float a, CallbackInfo ci) {
        if (EntityCache.ON) net.minecraft.client.renderer.SleepGlState.onColor(r, g, b, a);
    }

    @Inject(method = {"glNewList", "enableOutlineMode", "glPolygonMode", "enableColorLogic", "glLineWidth"}, at = @At("HEAD"))
    private static void sleep$other(CallbackInfo ci) {
        EntityCache.unsupported("other GL state");
    }
}
