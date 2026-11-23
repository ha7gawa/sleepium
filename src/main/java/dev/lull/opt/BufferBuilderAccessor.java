package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * RenderCacheMixins: appending complete recorded vertices to a BufferBuilder in one copy (1.20.1: one ByteBuffer,
 * nextElementByte the write position, elementIndex the element cursor - 0 between vertices).
 */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Accessor("buffer")
    ByteBuffer sleep$buffer();

    @Accessor("vertices")
    int sleep$vertices();

    @Accessor("vertices")
    void sleep$setVertices(int v);

    @Accessor("nextElementByte")
    int sleep$nextElementByte();

    @Accessor("nextElementByte")
    void sleep$setNextElementByte(int p);

    @Accessor("elementIndex")
    int sleep$elementIndex();

    @Accessor("format")
    VertexFormat sleep$format();

    @Accessor("mode")
    VertexFormat.Mode sleep$mode();

    @Accessor("renderedBufferCount")
    int sleep$renderedCount();

    @Accessor("renderedBufferPointer")
    int sleep$renderedPointer();

    @Accessor("building")
    boolean sleep$building();

    /** room for that many more bytes past nextElementByte (the buffer may be replaced: read sleep$buffer after it) */
    @Invoker("ensureCapacity")
    void sleep$ensureCapacity(int bytes);
}
