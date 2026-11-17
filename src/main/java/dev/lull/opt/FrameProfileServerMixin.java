package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.lull.optsupport.FrameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FrameProfile.SERVER (-Dlab.serverProfile, measuring): the server thread's profiler sums its tick's sections. */
@Mixin(MinecraftServer.class)
public abstract class FrameProfileServerMixin {
    @ModifyReturnValue(method = "getProfiler", at = @At("RETURN"))
    private ProfilerFiller sleep$serverProfile(ProfilerFiller p) {
        return FrameProfile.SERVER_ON && ((MinecraftServer) (Object) this).isSameThread() ? FrameProfile.SERVER : p;   // (isSameThread: BlockableEventLoop's, not shadowable here)
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void sleep$tickStart(java.util.function.BooleanSupplier hasTime, CallbackInfo ci) {
        if (FrameProfile.SERVER_ON) FrameProfile.tick();
    }
}
