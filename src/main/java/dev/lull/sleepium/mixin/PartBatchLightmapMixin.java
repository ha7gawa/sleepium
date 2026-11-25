package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.PartBatch;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PartBatch: the lightmap coordinates and the other GL state OpenGlHelper sets change after the batch. */
@Mixin(OpenGlHelper.class)
public abstract class PartBatchLightmapMixin {
    @Inject(method = {"setLightmapTextureCoords", "setActiveTexture", "setClientActiveTexture", "glBindBuffer", "glUseProgram", "glBindFramebuffer"}, at = @At("HEAD"))
    private static void sleep$state(CallbackInfo ci) {
        PartBatch.stateChange();
    }
}
