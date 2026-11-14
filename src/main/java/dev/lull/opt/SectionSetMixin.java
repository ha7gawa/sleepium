package dev.lull.opt;

import java.util.LinkedHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The occlusion graph's storage keeps its infos in flat arrays too (dev.lull.optsupport.SectionSet). Part of FrustumRegionMixin. */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkStorage")
public abstract class SectionSetMixin {
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "java/util/LinkedHashSet"))
    private LinkedHashSet<?> sleep$set(int size) {
        return new dev.lull.optsupport.SectionSet(size);
    }
}
