package dev.lull.opt;

import dev.lull.net.StallHeartbeat;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** StallHeartbeat: when the current server tick started (its timer pings the players when one lasts too long) */
@Mixin(MinecraftServer.class)
public abstract class StallHeartbeatTickMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void sleep$start(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StallHeartbeat.tickStart((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void sleep$end(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        StallHeartbeat.tickEnd();
    }
}
