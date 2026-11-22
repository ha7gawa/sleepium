package dev.lull.mixin;

import dev.lull.LightProbe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dlab.light.probe=true): block light sources of a chunk (the emitting blocks found and queued). */
@Mixin(BlockLightEngine.class)
public abstract class LightProbeBlockMixin {
    @Unique private long sleep$src;

    @Inject(method = "propagateLightSources", at = @At("HEAD"))
    private void sleep$head(ChunkPos pos, CallbackInfo ci) {
        sleep$src = System.nanoTime();
        if (dev.lull.LightTrace.inChunk(pos.x, pos.z)) dev.lull.LightTrace.log("propagateLightSources run " + pos);
        // (1.20.1, notes/84) a chunk's own sources asked for while the chunk is not available to the light engine:
        // what its holder looks like then (visible / updating map, level, each status' future)
        var getter = ((LightProbeEngineAccessor) (Object) this).sleep$chunks();
        if (getter.getChunkForLighting(pos.x, pos.z) == null && getter instanceof net.minecraft.server.level.ServerChunkCache scc) {
            var cm = (LightProbeChunkMapAccessor) scc.chunkMap;
            var vis = cm.sleep$visible(pos.toLong());
            var upd = cm.sleep$updating(pos.toLong());
            StringBuilder sb = new StringBuilder();
            var h = vis != null ? vis : upd;
            if (h != null)
                for (net.minecraft.world.level.chunk.ChunkStatus st : net.minecraft.world.level.chunk.ChunkStatus.getStatusList()) {
                    var e = h.getFutureIfPresentUnchecked(st).getNow(null);
                    sb.append(' ').append(st).append('=').append(e == null ? "pending" : e.left().isPresent() ? "chunk" : "unloaded");
                }
            System.out.println("[sleep/light] sources of " + pos + " asked with no chunk for lighting: visible holder " + (vis != null) + ", updating holder " + (upd != null)
                    + (upd != null && upd == vis ? " (same)" : "") + (h != null ? ", level " + h.getTicketLevel() + ", futures" + sb : "") + " on " + Thread.currentThread().getName());
        }
        if (dev.lull.LightHistory.ON) dev.lull.LightHistory.rec(dev.lull.LightHistory.SRC_SCAN, net.minecraft.core.SectionPos.asLong(pos.x, 0, pos.z), 0, 0, 0);
    }

    @Inject(method = "propagateLightSources", at = @At("RETURN"))
    private void sleep$ret(ChunkPos pos, CallbackInfo ci) {
        LightProbe.eng(false, 7, System.nanoTime() - sleep$src);
        LightProbe.eng(false, 8, 1);
    }
}
