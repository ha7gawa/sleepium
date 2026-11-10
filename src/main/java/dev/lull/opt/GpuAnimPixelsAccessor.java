package dev.lull.opt;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GpuAnim: an image's pixel memory (interpolated frames copied into the mapped upload ring). */
@Mixin(NativeImage.class)
public interface GpuAnimPixelsAccessor {
    @Accessor("pixels")
    long sleep$pixels();
}
