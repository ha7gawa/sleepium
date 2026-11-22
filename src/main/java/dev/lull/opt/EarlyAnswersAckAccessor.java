package dev.lull.opt;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** EarlyAnswers: the block-change acknowledgement vanilla keeps for the connection's next tick */
@Mixin(ServerGamePacketListenerImpl.class)
public interface EarlyAnswersAckAccessor {
    @Accessor("ackBlockChangesUpTo")
    int sleep$ack();

    @Accessor("ackBlockChangesUpTo")
    void sleep$setAck(int sequence);
}
