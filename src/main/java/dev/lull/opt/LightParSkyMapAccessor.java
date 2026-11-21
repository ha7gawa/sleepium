package dev.lull.opt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** LightPar: the sky storage's lowest section with data (hasLightDataAtOrBelow). */
@Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
public interface LightParSkyMapAccessor {
    @Accessor("currentLowestY")
    int sleep$lowestY();
}
