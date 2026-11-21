package dev.lull.opt;

import dev.lull.optsupport.TypeIter;
import java.util.Iterator;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** TypeIter: the compile's walk over a block model's render types without a new iterator a block (see there). */
@Mixin(SectionCompiler.class)
public abstract class RenderTypeIterMixin {
    @Redirect(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ChunkRenderTypeSet;iterator()Ljava/util/Iterator;"))
    private Iterator<RenderType> sleep$types(ChunkRenderTypeSet set) {
        if (set.getClass() == ChunkRenderTypeSet.class) {
            Iterator<RenderType> it = TypeIter.over(((ChunkRenderTypeSetAccessor) (Object) set).sleep$bits());
            if (it != null) return it;
        }
        return set.iterator();
    }
}
