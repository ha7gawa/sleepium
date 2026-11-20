package dev.lull.opt;

import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** AtlasDouble's swap: the GL texture an atlas draws from (AbstractTexture.id). */
@Mixin(AbstractTexture.class)
public interface AtlasIdAccessor {
    @Accessor("id")
    int sleep$id();

    @Accessor("id")
    void sleep$setId(int id);
}
