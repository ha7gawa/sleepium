package dev.lull.opt;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SectionSet: the frustum's planes and camera offset, to test a region or a box the way cubeInFrustum tests a box. */
@Mixin(Frustum.class)
public interface FrustumRegionAccessor {
    @Accessor("intersection")
    FrustumIntersection sleep$intersection();

    @Accessor("camX")
    double sleep$camX();

    @Accessor("camY")
    double sleep$camY();

    @Accessor("camZ")
    double sleep$camZ();
}
