package dev.lull.opt;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TrackFast's view of ChunkMap's view distance (part of lab.opt.TrackFast) */
@Mixin(ChunkMap.class)
public interface TrackFastChunkMapAccessor {
    @Accessor("viewDistance")
    int sleep$viewDistance();
}
