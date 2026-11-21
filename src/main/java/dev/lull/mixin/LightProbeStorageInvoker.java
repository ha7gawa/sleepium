package dev.lull.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Measurement only (-Dlab.light.probe): is a section stored, is light on in its column (LightProbeCheckNodeMixin). */
@Mixin(LayerLightSectionStorage.class)
public interface LightProbeStorageInvoker {
    @Invoker("storingLightForSection")
    boolean sleep$storing(long sectionPos);

    @Invoker("lightOnInSection")
    boolean sleep$lightOn(long sectionPos);

    @Invoker("getStoredLevel")
    int sleep$level(long packedPos);
}
