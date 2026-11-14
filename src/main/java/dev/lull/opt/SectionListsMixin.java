package dev.lull.opt;

import dev.lull.optsupport.SectionLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/section-lists (client): renderLevel's block entity loop and renderChunkLayer's loops (translucent, tripwire)
 * walk SectionLists' compact lists instead of every visible section; a compiled section arriving bumps the generation.
 * 1.20.1: the list is renderChunksInFrustum (RenderChunkInfo, a package-private type: raw lists here), compileChunks'
 * loop is compileSections', addRecentlyCompiledChunk is addRecentlyCompiledSection.
 */
@Mixin(LevelRenderer.class)
@SuppressWarnings("rawtypes")
public abstract class SectionListsMixin {
    private static final String FIELD = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunksInFrustum:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;";

    @Shadow @Final private ObjectArrayList renderChunksInFrustum;

    @Redirect(method = "renderLevel", at = @At(value = "FIELD", target = FIELD, opcode = Opcodes.GETFIELD))
    private ObjectArrayList sleep$blockEntities(LevelRenderer self) {
        return SectionLists.blockEntities(this.renderChunksInFrustum);
    }

    @Redirect(method = "renderChunkLayer", at = @At(value = "FIELD", target = FIELD, opcode = Opcodes.GETFIELD))
    private ObjectArrayList sleep$layer(LevelRenderer self) {
        return SectionLists.forLayer(this.renderChunksInFrustum);
    }

    @Redirect(method = "compileChunks", at = @At(value = "FIELD", target = FIELD, opcode = Opcodes.GETFIELD))
    private ObjectArrayList sleep$dirty(LevelRenderer self) {
        return SectionLists.dirtyVisible(this.renderChunksInFrustum);
    }

    @Inject(method = "addRecentlyCompiledChunk", at = @At("HEAD"))
    private void sleep$compiled(ChunkRenderDispatcher.RenderChunk section, CallbackInfo ci) {
        SectionLists.changed(section);
    }
}
