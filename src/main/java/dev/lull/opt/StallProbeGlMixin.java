package dev.lull.opt;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.lull.optsupport.GlFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** StallProbe (-Dlab.stallProbe): what each frame does to GL objects through vanilla's GlStateManager (GlFrame). */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class StallProbeGlMixin {
    @Inject(method = "_glGenBuffers", at = @At("HEAD"), require = 0)
    private static void sleep$genBuf(CallbackInfoReturnable<Integer> cir) { GlFrame.count(GlFrame.GEN_BUF, 1); }

    @Inject(method = "_glDeleteBuffers", at = @At("HEAD"), require = 0)
    private static void sleep$delBuf(int id, CallbackInfo ci) { GlFrame.count(GlFrame.DEL_BUF, 1); }

    @Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"), require = 0)
    private static void sleep$bufData(int target, java.nio.ByteBuffer data, int usage, CallbackInfo ci) {
        GlFrame.count(GlFrame.BUF_DATA, 1);
        GlFrame.count(GlFrame.BUF_BYTES, data.remaining());
    }

    @Inject(method = "_glBufferData(IJI)V", at = @At("HEAD"), require = 0)
    private static void sleep$bufSize(int target, long size, int usage, CallbackInfo ci) {
        GlFrame.count(GlFrame.BUF_DATA, 1);
        GlFrame.count(GlFrame.BUF_BYTES, size);
    }

    @Inject(method = "_glGenVertexArrays", at = @At("HEAD"), require = 0)
    private static void sleep$genVao(CallbackInfoReturnable<Integer> cir) { GlFrame.count(GlFrame.GEN_VAO, 1); }

    @Inject(method = "_glDeleteVertexArrays", at = @At("HEAD"), require = 0)
    private static void sleep$delVao(int id, CallbackInfo ci) { GlFrame.count(GlFrame.DEL_VAO, 1); }

    @Inject(method = "_genTexture", at = @At("HEAD"), require = 0)
    private static void sleep$genTex(CallbackInfoReturnable<Integer> cir) { GlFrame.count(GlFrame.GEN_TEX, 1); }

    @Inject(method = "_deleteTexture", at = @At("HEAD"), require = 0)
    private static void sleep$delTex(int id, CallbackInfo ci) { GlFrame.count(GlFrame.DEL_TEX, 1); }

    @Inject(method = "_texImage2D", at = @At("HEAD"), require = 0)
    private static void sleep$texImage(int target, int level, int internalFormat, int width, int height, int border, int format, int type,
                                       java.nio.IntBuffer pixels, CallbackInfo ci) {
        GlFrame.count(GlFrame.TEX_IMAGE, 1);
        GlFrame.count(GlFrame.TEX_BYTES, 4L * width * height);
    }

    @Inject(method = "glCompileShader", at = @At("HEAD"), require = 0)
    private static void sleep$compile(int shader, CallbackInfo ci) { GlFrame.count(GlFrame.COMPILE, 1); }

    @Inject(method = "glLinkProgram", at = @At("HEAD"), require = 0)
    private static void sleep$link(int program, CallbackInfo ci) { GlFrame.count(GlFrame.LINK, 1); }

    @Inject(method = "_glMapBuffer", at = @At("HEAD"), require = 0)
    private static void sleep$map(int target, int access, CallbackInfoReturnable<java.nio.ByteBuffer> cir) { GlFrame.count(GlFrame.MAP, 1); }
}
