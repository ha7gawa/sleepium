package dev.lull.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ServeMeter (notes/92): chunks ready for a player but not sent yet, and the sender's state */
@Mixin(PlayerChunkSender.class)
public interface ServeMeterSenderAccessor {
    @Accessor("pendingChunks")
    LongSet sleep$pending();

    @Accessor("unacknowledgedBatches")
    int sleep$unacked();

    @Accessor("desiredChunksPerTick")
    float sleep$desired();
}
