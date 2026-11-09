package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * RenderCacheMixin: the started builders of a buffer source (a renderer writing past its recorder is detected by them).
 * 1.20.1: a set of builders (the shared one, fixed ones, and EntityBatchMixin's per-type batches while they are open).
 */
@Mixin(MultiBufferSource.BufferSource.class)
public interface BufferSourceAccessor {
    @Accessor("startedBuffers")
    Set<BufferBuilder> sleep$started();

    @Accessor("fixedBuffers")
    Map<RenderType, BufferBuilder> sleep$fixed();

    /** the shared builder of the types without a fixed one (QuadGpu) */
    @Accessor("builder")
    BufferBuilder sleep$builder();

    /** the type the shared builder holds now */
    @Accessor("lastState")
    java.util.Optional<RenderType> sleep$lastState();
}
