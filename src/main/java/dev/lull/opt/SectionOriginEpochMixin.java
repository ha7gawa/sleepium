package dev.lull.opt;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chunk moved (the camera went far): SectionSet rereads its origins. Part of FrustumRegionMixin. */
@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class SectionOriginEpochMixin {
    @Inject(method = "setOrigin", at = @At("HEAD"))
    private void sleep$moved(int x, int y, int z, CallbackInfo ci) {
        dev.lull.optsupport.SectionSet.originEpoch++;
    }
}
