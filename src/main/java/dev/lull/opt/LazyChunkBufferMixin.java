package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.LazyChunkBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LazyChunkBuffers: a section's buffer made while the view area is built gets no GL names in its constructor; its first
 * bind (vanilla binds before every upload and every draw) makes them. isInvalid() (-1) stays false. A buffer closed
 * before its first bind still has name 0: glDeleteBuffers(0) is a no-op, but on Linux vanilla's _glDeleteBuffers first
 * binds the name to GL_ARRAY_BUFFER and orphans it with glBufferData - with 0 bound that is GL_INVALID_OPERATION "no
 * buffer bound" (Mesa on the Pi: ~217 000 errors at each view-area rebuild / world exit, every one through the GL debug
 * callback and the log). Name 0 is skipped here; on Linux the GL_ARRAY_BUFFER binding is left at 0 as vanilla's
 * sequence leaves it.
 */
@Mixin(VertexBuffer.class)
public abstract class LazyChunkBufferMixin {
    @Shadow private int vertexBufferId;
    @Shadow private int indexBufferId;
    @Shadow private int arrayObjectId;

    @Unique private boolean sleep$unnamed;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"))
    private int sleep$noBuffer(Operation<Integer> original) {
        if (LazyChunkBuffers.lazyHere()) {
            sleep$unnamed = true;
            return 0;
        }
        return original.call();
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int sleep$noArray(Operation<Integer> original) {
        if (sleep$unnamed) {
            LazyChunkBuffers.made++;
            return 0;
        }
        return original.call();
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glDeleteBuffers(I)V"))
    private void sleep$deleteNamed(int buffer, Operation<Void> original) {
        if (buffer == 0) LazyChunkBuffers.skipUnnamedDelete();
        else original.call(buffer);
    }

    @Inject(method = "bind", at = @At("HEAD"))
    private void sleep$name(CallbackInfo ci) {
        if (!sleep$unnamed) return;
        sleep$unnamed = false;
        vertexBufferId = GlStateManager._glGenBuffers();
        indexBufferId = GlStateManager._glGenBuffers();
        arrayObjectId = GlStateManager._glGenVertexArrays();
        LazyChunkBuffers.named++;
    }
}
