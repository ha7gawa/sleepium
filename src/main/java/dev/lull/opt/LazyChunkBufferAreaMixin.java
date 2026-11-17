package dev.lull.opt;

import dev.lull.optsupport.LazyChunkBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LazyChunkBufferMixin: the buffers made while the view area builds its sections are the lazy ones. */
@Mixin(ViewArea.class)
public abstract class LazyChunkBufferAreaMixin {
    @Inject(method = "createSections", at = @At("HEAD"))
    private void sleep$lazyOn(SectionRenderDispatcher dispatcher, CallbackInfo ci) {
        LazyChunkBuffers.building = Thread.currentThread();
    }

    @Inject(method = "createSections", at = @At("RETURN"))
    private void sleep$lazyOff(SectionRenderDispatcher dispatcher, CallbackInfo ci) {
        LazyChunkBuffers.building = null;
    }
}
