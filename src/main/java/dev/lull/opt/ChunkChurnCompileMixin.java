package dev.lull.opt;

import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dlab.chunkChurn (measuring): sections meshed, and how many of them had the same inputs as the section's last mesh
 * (ChunkChurn.inputs: the 18^3 block states and light around it) - what a "same inputs, keep the mesh" rule would skip.
 */
@Mixin(SectionCompiler.class)
public abstract class ChunkChurnCompileMixin {
    @Inject(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At("HEAD"))
    private void sleep$compiled(SectionPos pos, RenderChunkRegion region, com.mojang.blaze3d.vertex.VertexSorting sorting,
            net.minecraft.client.renderer.SectionBufferBuilderPack pack, java.util.List<?> extra, CallbackInfoReturnable<?> cir) {
        dev.lull.optsupport.ChunkChurn.compiled(pos, region);
    }

    @Inject(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At("RETURN"))
    private void sleep$results(SectionPos pos, RenderChunkRegion region, com.mojang.blaze3d.vertex.VertexSorting sorting,
            net.minecraft.client.renderer.SectionBufferBuilderPack pack, java.util.List<?> extra, CallbackInfoReturnable<net.minecraft.client.renderer.chunk.SectionCompiler.Results> cir) {
        dev.lull.optsupport.ChunkChurn.results(pos, region, cir.getReturnValue());
    }
}
