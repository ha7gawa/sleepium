package dev.lull.opt;

import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** OcclusionFastMixin: the view area's grid (a section's index -> its column). */
@Mixin(ViewArea.class)
public interface OcclusionViewAreaAccessor {
    @Accessor("sectionGridSizeX")
    int sleep$gridX();

    @Accessor("sectionGridSizeY")
    int sleep$gridY();

    @Accessor("sectionGridSizeZ")
    int sleep$gridZ();
}
