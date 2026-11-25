package dev.lull.sleepium.mixin;

import net.minecraft.client.model.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TexturedQuad.class)
public interface TexturedQuadAccessor {
    @Accessor("invertNormal")
    boolean sleep$inverted();
}
