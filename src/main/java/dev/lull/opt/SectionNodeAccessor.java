package dev.lull.opt;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SectionSet: a graph node's section (SectionOcclusionGraph.Node is package-private). */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$Node")
public interface SectionNodeAccessor {
    @Accessor("section")
    SectionRenderDispatcher.RenderSection sleep$section();
}
