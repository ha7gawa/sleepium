package dev.lull.opt;

import dev.lull.net.NetReactor;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NetReactor: sends inside the server tick are batched; its end is a flush point (one task per connection). */
@Mixin(MinecraftServer.class)
public abstract class NetReactorTickMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void sleep$start(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        NetReactor.onTickStart();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void sleep$flush(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        NetReactor.onServerTick();
    }

    /** NetFlushLater: the tick's batches leave where vanilla flushes ("send chunks", the end of tickChildren) */
    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void sleep$childrenEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        NetReactor.onChildrenEnd();
    }
}
