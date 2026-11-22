package dev.lull.opt;

import dev.lull.optsupport.ChunkSendPar;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * lab.opt.ChunkSendPar: vanilla's sendNextChunks split into its decision (sleep$plan, made for every player at the start
 * of the phase) and its sends (here, at the player's turn, with the packets ChunkSendPar built in parallel).
 */
@Mixin(PlayerChunkSender.class)
public abstract class ChunkSendParMixin implements ChunkSendPar.Planner {
    @Shadow @Final private LongSet pendingChunks;
    @Shadow private float desiredChunksPerTick;
    @Shadow private float batchQuota;
    @Shadow private int unacknowledgedBatches;
    @Shadow private int maxUnacknowledgedBatches;

    @Shadow
    private List<LevelChunk> collectChunksToSend(ChunkMap chunkMap, ChunkPos chunkPos) {
        throw new AssertionError();
    }

    @Unique private boolean sleep$hasPlan;
    @Unique private List<LevelChunk> sleep$planned;

    @Override
    public List<LevelChunk> sleep$plan(ServerPlayer player) {
        // vanilla's sendNextChunks up to the sends: the same reads and writes of this sender, in the same order
        sleep$hasPlan = true;
        sleep$planned = null;
        if (this.unacknowledgedBatches < this.maxUnacknowledgedBatches) {
            float f = Math.max(1.0F, this.desiredChunksPerTick);
            this.batchQuota = Math.min(this.batchQuota + this.desiredChunksPerTick, f);
            if (!(this.batchQuota < 1.0F) && !this.pendingChunks.isEmpty()) {
                List<LevelChunk> list = this.collectChunksToSend(player.serverLevel().getChunkSource().chunkMap, player.chunkPosition());
                if (!list.isEmpty()) sleep$planned = list;
            }
        }
        return sleep$planned;
    }

    @Override
    public void sleep$dropPlan() {
        sleep$hasPlan = false;
        sleep$planned = null;
    }

    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true)
    private void sleep$send(ServerPlayer player, CallbackInfo ci) {
        if (!sleep$hasPlan) return;   // no plan this tick: vanilla's method
        ci.cancel();
        List<LevelChunk> list = sleep$planned;
        sleep$hasPlan = false;
        sleep$planned = null;
        if (list == null) return;
        ServerLevel level = player.serverLevel();
        ServerGamePacketListenerImpl listener = player.connection;
        this.unacknowledgedBatches++;
        listener.send(ClientboundChunkBatchStartPacket.INSTANCE);
        for (LevelChunk chunk : list) ChunkSendPar.sendChunk(listener, level, chunk);
        listener.send(new ClientboundChunkBatchFinishedPacket(list.size()));
        this.batchQuota = this.batchQuota - (float) list.size();
    }
}
