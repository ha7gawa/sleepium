package dev.lull.opt;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** TrackFast's view of ChunkMap's package-private checks (part of lab.opt.TrackFast) */
@Mixin(ChunkMap.class)
public interface TrackFastChunkMapInvoker {
    @Invoker("getPlayerViewDistance")
    int sleep$viewDistance(ServerPlayer player);

    @Invoker("isChunkTracked")
    boolean sleep$chunkTracked(ServerPlayer player, int x, int z);
}
