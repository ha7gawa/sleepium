package dev.lull.opt;

import dev.lull.optsupport.ChunkSendPar;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** lab.opt.ChunkSendPar: the plan and the parallel build at the start of the tick's "send chunks" phase. */
@Mixin(MinecraftServer.class)
public abstract class ChunkSendParServerMixin {
    @Inject(method = "tickChildren", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=send chunks", shift = At.Shift.AFTER))
    private void sleep$prepare(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ChunkSendPar.prepare((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void sleep$end(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ChunkSendPar.end();
    }
}
