package dev.lull.opt;

import dev.lull.optsupport.RegionStates;
import java.util.List;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RegionStates: the box of the section being meshed, for its region's reads (see there). */
@Mixin(SectionCompiler.class)
public abstract class RegionStatesCompileMixin {
    private static final String COMPILE = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

    @Inject(method = COMPILE, at = @At("HEAD"))
    private void sleep$begin(SectionPos pos, RenderChunkRegion region, com.mojang.blaze3d.vertex.VertexSorting sorting, SectionBufferBuilderPack pack,
                             List<?> extra, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        RegionStates.begin(region, pos);
    }

    @Inject(method = COMPILE, at = @At("RETURN"))
    private void sleep$end(SectionPos pos, RenderChunkRegion region, com.mojang.blaze3d.vertex.VertexSorting sorting, SectionBufferBuilderPack pack,
                           List<?> extra, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        RegionStates.end();
    }
}
