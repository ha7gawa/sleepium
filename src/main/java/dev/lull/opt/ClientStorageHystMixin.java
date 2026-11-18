package dev.lull.opt;

import dev.lull.optsupport.Hysteresis;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hysteresis: our client keeps K - 2 more chunks around the centre (vanilla keeps R + 3: enough for K <= 2). */
@Mixin(ClientChunkCache.class)
public abstract class ClientStorageHystMixin {
    @Inject(method = "calculateStorageRange", at = @At("RETURN"), cancellable = true)
    private static void sleep$range(int viewDistance, CallbackInfoReturnable<Integer> cir) {
        if (Hysteresis.K > 2) cir.setReturnValue(cir.getReturnValueI() + Hysteresis.K - 2);
    }
}
