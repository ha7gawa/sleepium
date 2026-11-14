package dev.lull.opt;

import java.util.List;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk sections culled by the frustum on flat arrays (dev.lull.optsupport.FrustumRegions, SectionSet):
 * addSectionsInFrustum's walk over the occlusion graph's linked set replaced by a walk over arrays, the same sections in
 * the same order. Fast camera turns at large view distances: vanilla re-culls on every 2 degrees, a pointer chase
 * through every section (32 chunks: ~7 ms a turn).
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class FrustumRegionMixin {
    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"))
    private void sleep$begin(Frustum frustum, List<SectionRenderDispatcher.RenderSection> sections, CallbackInfo ci) {
        dev.lull.optsupport.FrustumRegions.begin(frustum, sections);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void sleep$end(Frustum frustum, List<SectionRenderDispatcher.RenderSection> sections, CallbackInfo ci) {
        dev.lull.optsupport.FrustumRegions.end(sections.size());
    }

    @Redirect(method = "addSectionsInFrustum", at = @At(value = "INVOKE", target = "Ljava/util/LinkedHashSet;iterator()Ljava/util/Iterator;"))
    private java.util.Iterator<?> sleep$walk(java.util.LinkedHashSet<?> set) {
        return dev.lull.optsupport.FrustumRegions.walk(set);
    }
}
