package dev.lull.opt;

import net.minecraft.Util;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LabPoolsMixin (notes/88): a dedicated server whose stop threw (11-21: a mod's SavedData with a null key, NeoForge's
 * IOUtilities rethrowing it in ServerLevel.save) never reaches Util.shutdownExecutors. Vanilla's IO pool is a cached
 * pool: its non-daemon threads retire after 60 s idle and the JVM exits; LabRuntime's IO workers stay parked and the
 * process hung forever. At the server's exit the IO pool is shut down if the stop did not do it: queued writes still
 * run (LabRuntime's workers drain before they end), then the workers end and the JVM exits as vanilla's would.
 */
@Mixin(DedicatedServer.class)
public abstract class LabPoolsExitMixin {
    /** test only: -Dlab.test.stopThrows=true makes the stop throw at once (the exit after a failed stop, notes/88) */
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sleep$testThrow(CallbackInfo ci) {
        if (Boolean.getBoolean("lab.test.stopThrows")) throw new IllegalStateException("lab.test.stopThrows: the stop fails on purpose");
    }

    @Inject(method = "onServerExit", at = @At("TAIL"))
    private void sleep$ioPoolEnds(CallbackInfo ci) {
        if (Util.ioPool() instanceof dev.lab.runtime.LabRuntime rt && !rt.isShutdown()) {
            System.out.println("[sleep/opt] server exit without Util.shutdownExecutors (the stop threw): the IO pool ends after its queued work");
            rt.shutdown();
        }
    }
}
