package dev.lull.opt;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.util.profiling.ProfileResults;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.ResultField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only (-Dlab.opt.ClientTickProfileMixin=true): every client tick (Minecraft.tick) recorded through
 * vanilla's own profiler sections (push / pop, an ActiveProfiler teed with the game's), the time per section path
 * summed over the run - the client tick's parts, not a sampled guess (dev.lull.optsupport.ClientTickProfile).
 */
@Mixin(Minecraft.class)
public abstract class ClientTickProfileMixin {
    @Shadow private ProfilerFiller profiler;
    @Unique private ProfilerFiller sleep$saved;
    @Unique private ActiveProfiler sleep$active;

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$start(CallbackInfo ci) {
        sleep$active = new ActiveProfiler(net.minecraft.Util.timeSource, () -> 0, false);
        sleep$active.startTick();
        sleep$saved = profiler;
        profiler = ProfilerFiller.tee(dev.lull.optsupport.ClientTickProfile.gate(sleep$active), profiler);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$end(CallbackInfo ci) {
        ActiveProfiler a = sleep$active;
        sleep$active = null;
        if (sleep$saved != null) profiler = sleep$saved;
        sleep$saved = null;
        if (a == null) return;
        a.endTick();
        dev.lull.optsupport.ClientTickProfile.add(a.getResults());
    }
}
