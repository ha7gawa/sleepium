package dev.lull.opt;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** RenderCacheMixin (issue #11): a byte buffer's write position, to take a renderer's escaped writes back */
@Mixin(ByteBufferBuilder.class)
public interface ByteBufferBuilderAccessor {
    @Accessor("writeOffset")
    int sleep$writeOffset();

    @Accessor("writeOffset")
    void sleep$setWriteOffset(int o);

    @Accessor("nextResultOffset")
    int sleep$nextResult();

    @Accessor("generation")
    int sleep$generation();
}
