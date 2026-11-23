package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** RenderCacheMixin: the started builders of a buffer source (a renderer writing past its recorder is detected by them). */
@Mixin(MultiBufferSource.BufferSource.class)
public interface BufferSourceAccessor {
    @Accessor("startedBuilders")
    Map<RenderType, BufferBuilder> sleep$started();

    @Accessor("fixedBuffers")
    java.util.SequencedMap<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> sleep$fixed();

    @Accessor("sharedBuffer")
    com.mojang.blaze3d.vertex.ByteBufferBuilder sleep$shared();

    @Accessor("lastSharedType")
    RenderType sleep$lastShared();

    @Accessor("lastSharedType")
    void sleep$setLastShared(RenderType t);
}
