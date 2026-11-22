package dev.lull.opt;

import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * LightDirect with -Dlab.light.ownThread=true (notes/84): the level's light mailbox runs on its own thread
 * (optsupport/LightThread, parked when there is nothing to do) instead of taking turns on the worker pool, whose queue
 * is full of generation work while chunks wait for their light. The mailbox still runs its tasks one at a time, in order.
 */
@Mixin(ChunkMap.class)
public abstract class LightDirectThreadMixin {
    @ModifyArg(method = "<init>", index = 0, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/util/thread/ProcessorMailbox;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ProcessorMailbox;"))
    private Executor sleep$lightThread(Executor dispatcher) {
        return dev.lull.optsupport.LightThread.ON ? dev.lull.optsupport.LightThread.EXEC : dispatcher;
    }
}
