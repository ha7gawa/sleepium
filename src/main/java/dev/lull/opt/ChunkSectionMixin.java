package dev.lull.opt;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.ChunkBufferTag;
import dev.lull.optsupport.ChunkMultiDraw;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChunkMultiDraw: tags the section's layer buffers; a reset (origin change, release) frees its arena entries.
 * 1.20.1: the section is ChunkRenderDispatcher.RenderChunk; it has no setCompiled (RebuildTask sets the compiled
 * reference itself), so a new compile is seen at LevelRenderer.addRecentlyCompiledChunk (ChunkCompiledMixin).
 */
@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class ChunkSectionMixin implements ChunkMultiDraw.ChunkEntries {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @org.spongepowered.asm.mixin.Unique private final Object[] sleep$md = new Object[16];
    @org.spongepowered.asm.mixin.Unique private int sleep$visIdx = -1, sleep$visBuild = -1, sleep$dirtyBuild = -1, sleep$epoch;

    @Override
    public int sleep$epoch() {
        return sleep$epoch;
    }

    @Override
    public Object[] sleep$md() {
        return sleep$md;
    }

    @Override
    public int sleep$visIdx(int b) {
        return sleep$visBuild == b ? sleep$visIdx : -1;
    }

    @Override
    public void sleep$setVis(int idx, int b) {
        sleep$visIdx = idx;
        sleep$visBuild = b;
    }

    @Override
    public int sleep$dirtyBuild() {
        return sleep$dirtyBuild;
    }

    @Override
    public void sleep$setDirtyBuild(int b) {
        sleep$dirtyBuild = b;
    }

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void sleep$dirty(boolean playerChanged, CallbackInfo ci) {
        sleep$epoch++;
        // (1.21.1 also told LightBoxes here when part batching's lit boxes are on: they come with PartBatch's port)
    }

    /** after the field is set: SectionLists' "maybe dirty" never lags the chunk (setDirty can come from another thread) */
    @Inject(method = "setDirty", at = @At("RETURN"))
    private void sleep$dirtied(boolean playerChanged, CallbackInfo ci) {
        dev.lull.optsupport.SectionLists.dirtied((ChunkRenderDispatcher.RenderChunk) (Object) this);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$tag(CallbackInfo ci) {
        dev.lull.optsupport.SectionLists.born((ChunkRenderDispatcher.RenderChunk) (Object) this);
        if (((ChunkRenderDispatcher.RenderChunk) (Object) this).index < 0) return;   // not the level's (Sable's ship sections: local origins)
        for (var e : buffers.entrySet()) ((ChunkBufferTag) e.getValue()).sleep$tag((ChunkRenderDispatcher.RenderChunk) (Object) this, e.getKey());
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void sleep$reset(CallbackInfo ci) {
        sleep$epoch++;
        ChunkMultiDraw.release((ChunkRenderDispatcher.RenderChunk) (Object) this);
    }

    /** reset set the chunk uncompiled and dirty */
    @Inject(method = "reset", at = @At("RETURN"))
    private void sleep$wasReset(CallbackInfo ci) {
        dev.lull.optsupport.SectionLists.changed((ChunkRenderDispatcher.RenderChunk) (Object) this);
        dev.lull.optsupport.SectionLists.dirtied((ChunkRenderDispatcher.RenderChunk) (Object) this);
    }
}
