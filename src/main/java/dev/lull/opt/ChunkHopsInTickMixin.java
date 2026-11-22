package dev.lull.opt;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decoupled server, exact part 3 (notes/92, lab: {@code -Dlab.opt.ChunkHopsInTick=true}): chunk loading does not wait a
 * whole tick for each of its main-thread steps.
 * <p>
 * Loading / generating a chunk hops to the server thread between its steps (the chunk source's main-thread mailbox:
 * distance-manager updates, the steps' continuations, promotion to a full chunk). Vanilla runs that mailbox between
 * ticks only while the server has time - a server that is behind gets one short window after each tick, closed the
 * first moment the mailbox is empty (a step still on a worker) - so every hop costs about a tick: at 100 ms ticks a
 * 20 blocks/s flight left 80 of ~150 chunks in view missing (notes/92).
 * <p>
 * Here, right before the tick's "send chunks" phase, the mailbox runs what is already queued (no waiting), up to
 * {@code lab.chunkHops.budgetMs} (10 ms) - chunks that became ready during the tick go to the players in that same tick.
 * Exact: vanilla itself runs this mailbox at arbitrary points inside a tick (every blocking getChunk drives it with
 * managedBlock), so this is a schedule vanilla can produce; which tick a chunk becomes full in already depends on the
 * wall clock in vanilla.
 */
@Mixin(MinecraftServer.class)
public abstract class ChunkHopsInTickMixin {
    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @Inject(method = "tickChildren", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=send chunks"))
    private void sleep$hops(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        dev.lull.optsupport.ChunkHops.run(this.getAllLevels());
    }
}
