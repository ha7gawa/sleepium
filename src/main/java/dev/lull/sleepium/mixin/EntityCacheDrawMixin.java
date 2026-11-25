package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.EntityCache;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EntityCache's capture: a Tessellator buffer about to be drawn. */
@Mixin(WorldVertexBufferUploader.class)
public abstract class EntityCacheDrawMixin {
    @Inject(method = "draw", at = @At("HEAD"))
    private void sleep$draw(BufferBuilder b, CallbackInfo ci) {
        if (EntityCache.capturing() && b.getVertexCount() > 0) EntityCache.buffer(b.getByteBuffer(), b.getVertexFormat(), b.getDrawMode(), b.getVertexCount());
    }
}
