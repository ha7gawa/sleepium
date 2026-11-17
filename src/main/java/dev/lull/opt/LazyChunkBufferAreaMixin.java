package dev.lull.opt;

import dev.lull.optsupport.LazyChunkBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LazyChunkBufferMixin: the buffers made while the view area builds its sections are the lazy ones. */
@Mixin(ViewArea.class)
public abstract class LazyChunkBufferAreaMixin {
    @Inject(method = "createChunks", at = @At("HEAD"))
    private void sleep$lazyOn(ChunkRenderDispatcher dispatcher, CallbackInfo ci) {
        LazyChunkBuffers.building = Thread.currentThread();
    }

    @Inject(method = "createChunks", at = @At("RETURN"))
    private void sleep$lazyOff(ChunkRenderDispatcher dispatcher, CallbackInfo ci) {
        LazyChunkBuffers.building = null;
    }
}
