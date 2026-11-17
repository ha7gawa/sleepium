package dev.lull.optsupport;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** On VertexBuffer (ChunkUploadMixin): the render section and chunk layer a section buffer belongs to (null otherwise). */
public interface ChunkBufferTag {
    void sleep$tag(SectionRenderDispatcher.RenderSection section, RenderType layer);

    SectionRenderDispatcher.RenderSection sleep$tagSection();

    RenderType sleep$tagLayer();

    /** UploadBindMixin: a section buffer whose bind was left out (our arena takes its mesh); bound if vanilla uploads it after all */
    final class Deferred {
        public static Object buffer;
        public static long skipped, boundLate;
    }
}
