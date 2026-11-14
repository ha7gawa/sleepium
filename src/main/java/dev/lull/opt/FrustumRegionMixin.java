package dev.lull.opt;

import java.util.List;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk sections culled by the frustum region by region (dev.lull.optsupport.FrustumRegions): the same list in the same
 * order, a test per 64-block region instead of per section except on the frustum's border. Fast camera turns at large
 * view distances (32: 2.4 ms a frame in terrain_setup on a desktop).
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

    @Inject(method = "update", at = @At("HEAD"))
    private void sleep$updateBegin(CallbackInfo ci) {
        dev.lull.optsupport.FrustumRegions.updateAt = System.nanoTime();
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void sleep$updateEnd(CallbackInfo ci) {
        dev.lull.optsupport.FrustumRegions.updateNs += System.nanoTime() - dev.lull.optsupport.FrustumRegions.updateAt;
        dev.lull.optsupport.FrustumRegions.updates++;
    }

    @Redirect(method = "addSectionsInFrustum", at = @At(value = "INVOKE", target = "Ljava/util/LinkedHashSet;iterator()Ljava/util/Iterator;"))
    private java.util.Iterator<?> sleep$walk(java.util.LinkedHashSet<?> set) {
        return dev.lull.optsupport.FrustumRegions.walk(set);
    }

    @Redirect(method = "addSectionsInFrustum", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean sleep$visible(Frustum frustum, AABB box) {
        return dev.lull.optsupport.FrustumRegions.visible(frustum, box);
    }
}
