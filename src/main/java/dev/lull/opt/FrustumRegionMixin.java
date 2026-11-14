package dev.lull.opt;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk sections culled by the frustum on flat arrays (dev.lull.optsupport.FrustumRegions, SectionSet): applyFrustum's
 * walk over the occlusion graph's linked set replaced by a walk over arrays, the same infos in the same order. Fast
 * camera turns at large view distances: vanilla re-culls on every 2 degrees, a pointer chase through every section.
 * 1.20.1: LevelRenderer.applyFrustum, the list holds RenderChunkInfo (1.21.1: SectionOcclusionGraph.addSectionsInFrustum).
 */
@Mixin(LevelRenderer.class)
@SuppressWarnings("rawtypes")
public abstract class FrustumRegionMixin {
    @Shadow @Final private ObjectArrayList renderChunksInFrustum;

    @Inject(method = "applyFrustum", at = @At("HEAD"))
    private void sleep$begin(Frustum frustum, CallbackInfo ci) {
        dev.lull.optsupport.FrustumRegions.begin(frustum, this.renderChunksInFrustum);
    }

    @Inject(method = "applyFrustum", at = @At("RETURN"))
    private void sleep$end(Frustum frustum, CallbackInfo ci) {
        dev.lull.optsupport.FrustumRegions.end(this.renderChunksInFrustum.size());
    }

    @Redirect(method = "applyFrustum", at = @At(value = "INVOKE", target = "Ljava/util/LinkedHashSet;iterator()Ljava/util/Iterator;"))
    private java.util.Iterator<?> sleep$walk(java.util.LinkedHashSet<?> set) {
        return dev.lull.optsupport.FrustumRegions.walk(set);
    }
}
