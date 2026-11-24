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
        return worker() ? InactiveProfiler.INSTANCE : p;
    }

    /**
     * the supplier mobs keep from their creation (GoalSelector, PathNavigation...): asked on a worker during a run it gives
     * the inactive profiler too - with vanilla's profiler on (/debug start, /perf start) a run's goal ticks otherwise
     * pushed into the server thread's profiler from the workers (ConcurrentModificationException, the server stopped)
     */
    @ModifyReturnValue(method = "getProfilerSupplier", at = @At("RETURN"))
    private java.util.function.Supplier<ProfilerFiller> sleep$specProfilerSupplier(java.util.function.Supplier<ProfilerFiller> s) {
        return () -> worker() ? InactiveProfiler.INSTANCE : s.get();
    }

    /** a worker of a mob run or of a parallel items run (the server thread helps in both: it keeps the real profiler) */
    private boolean worker() {
        if (dev.lull.optsupport.SpecTicks.onWorker()) return true;
        return dev.lull.optsupport.ServerParallelItems.inJob() && (Object) this instanceof net.minecraft.server.level.ServerLevel sl && !sl.getServer().isSameThread();
    }
}
