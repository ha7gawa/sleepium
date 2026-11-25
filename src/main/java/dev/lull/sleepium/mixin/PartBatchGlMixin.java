package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.PartBatch;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PartBatch's view of GL: every state change or draw lets the batch go first; the matrix calls are mirrored. */
@Mixin(GlStateManager.class)
public abstract class PartBatchGlMixin {
    @Inject(method = {"pushAttrib", "popAttrib", "disableAlpha", "enableAlpha", "alphaFunc", "enableLighting", "disableLighting", "enableLight", "disableLight",
            "enableColorMaterial", "disableColorMaterial", "colorMaterial", "glLight", "glLightModel", "glNormal3f", "disableDepth", "enableDepth", "depthFunc",
            "depthMask", "disableBlend", "enableBlend", "blendFunc", "tryBlendFuncSeparate", "glBlendEquation", "enableOutlineMode", "disableOutlineMode",
            "enableFog", "disableFog", "setFog", "setFogDensity", "setFogStart", "setFogEnd", "glFog", "glFogi", "enableCull", "disableCull", "cullFace",
            "glPolygonMode", "enablePolygonOffset", "disablePolygonOffset", "doPolygonOffset", "enableColorLogic", "disableColorLogic", "colorLogicOp",
            "enableTexGenCoord", "disableTexGenCoord", "texGen", "setActiveTexture", "enableTexture2D", "disableTexture2D", "glTexEnv", "glTexEnvi", "glTexEnvf",
            "glTexParameterf", "glTexParameteri", "deleteTexture", "bindTexture", "glTexImage2D", "glTexSubImage2D", "glCopyTexSubImage2D", "enableNormalize",
            "disableNormalize", "shadeModel", "enableRescaleNormal", "disableRescaleNormal", "viewport", "colorMask", "clear", "color", "glTexCoord2f", "glVertex3f",
            "glNormalPointer", "glTexCoordPointer", "glVertexPointer", "glColorPointer", "glDisableClientState", "glEnableClientState", "glBegin", "glEnd",
            "glDrawArrays", "glLineWidth", "callList", "glReadPixels", "enableBlendProfile", "disableBlendProfile"}, at = @At("HEAD"))
    private static void sleep$state(CallbackInfo ci) {
        PartBatch.stateChange();
    }

    @Inject(method = "glNewList", at = @At("HEAD"))
    private static void sleep$newList(int list, int mode, CallbackInfo ci) {
        PartBatch.newList();
    }

    @Inject(method = "glEndList", at = @At("HEAD"))
    private static void sleep$endList(CallbackInfo ci) {
        PartBatch.endList();
    }

    @Inject(method = "matrixMode", at = @At("HEAD"))
    private static void sleep$mode(int mode, CallbackInfo ci) {
        PartBatch.matrixMode(mode);
    }

    @Inject(method = "pushMatrix", at = @At("HEAD"))
    private static void sleep$push(CallbackInfo ci) {
        PartBatch.push();
    }

    @Inject(method = "popMatrix", at = @At("HEAD"))
    private static void sleep$pop(CallbackInfo ci) {
        PartBatch.pop();
    }

    @Inject(method = "translate(FFF)V", at = @At("HEAD"))
    private static void sleep$translateF(float x, float y, float z, CallbackInfo ci) {
        PartBatch.translate(x, y, z);
    }

    @Inject(method = "translate(DDD)V", at = @At("HEAD"))
    private static void sleep$translateD(double x, double y, double z, CallbackInfo ci) {
        PartBatch.translate(x, y, z);
    }

    @Inject(method = "scale(FFF)V", at = @At("HEAD"))
    private static void sleep$scaleF(float x, float y, float z, CallbackInfo ci) {
        PartBatch.scale(x, y, z);
    }

    @Inject(method = "scale(DDD)V", at = @At("HEAD"))
    private static void sleep$scaleD(double x, double y, double z, CallbackInfo ci) {
        PartBatch.scale(x, y, z);
    }

    @Inject(method = "rotate(FFFF)V", at = @At("HEAD"))
    private static void sleep$rotate(float a, float x, float y, float z, CallbackInfo ci) {
        PartBatch.rotate(a, x, y, z);
    }

    @Inject(method = "multMatrix", at = @At("HEAD"))
    private static void sleep$mult(FloatBuffer m, CallbackInfo ci) {
        PartBatch.mult(m);
    }

    @Inject(method = {"loadIdentity", "ortho"}, at = @At("HEAD"))
    private static void sleep$lost(CallbackInfo ci) {
        PartBatch.invalidate();
    }
}
