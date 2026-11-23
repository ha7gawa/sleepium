package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** part of SpecTicks (lab): a run's workers get the inactive profiler (vanilla's profiler is the server thread's alone). */
@Mixin(Level.class)
public abstract class ServerParallelItemsProfilerMixin {
    @ModifyReturnValue(method = "getProfiler", at = @At("RETURN"))
    private ProfilerFiller sleep$specProfiler(ProfilerFiller p) {
        return dev.lull.optsupport.SpecTicks.onWorker() ? InactiveProfiler.INSTANCE : p;
    }
}
