package dev.lull.opt;

import dev.lull.optsupport.LazySections;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** LazySections: a region made (render thread) - its chunk copies copy the sections around its height now. */
@Mixin(RenderRegionCache.class)
public abstract class LazySectionRegionMixin {
    @Inject(method = "createRegion(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/SectionPos;Z)Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;",
            at = @At("RETURN"))
    private void sleep$copyAround(Level level, SectionPos pos, boolean nullForEmpty, CallbackInfoReturnable<RenderChunkRegion> cir) {
        RenderChunkRegion r = cir.getReturnValue();
        if (r == null || !LazySections.ON) return;
        for (RenderChunk c : r.chunks) if (c != null) ((LazySections.Chunk) (Object) c).sleep$ensureAround(pos.y());
    }
}
