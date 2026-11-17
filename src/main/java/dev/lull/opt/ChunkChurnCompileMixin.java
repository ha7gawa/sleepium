package dev.lull.opt;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.chunkChurn (measuring): sections meshed (ChunkChurn). */
@Mixin(SectionCompiler.class)
public abstract class ChunkChurnCompileMixin {
    @Inject(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At("HEAD"))
    private void sleep$compiled(CallbackInfoReturnable<?> cir) {
        dev.lull.optsupport.ChunkChurn.compiled();
    }
}
