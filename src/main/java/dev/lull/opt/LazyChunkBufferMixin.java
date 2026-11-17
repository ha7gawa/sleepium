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
 * bind (vanilla binds before every upload and every draw) makes them. Deleting name 0 at close is a no-op, and
 * isInvalid() (-1) stays false.
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
