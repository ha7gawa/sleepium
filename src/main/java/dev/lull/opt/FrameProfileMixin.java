package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.lull.optsupport.FrameProfile;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FrameProfile (-Dlab.frameProfile, measuring): on the frame thread, while it draws, the profiler is FrameProfile's. */
@Mixin(Minecraft.class)
public abstract class FrameProfileMixin {
    @ModifyReturnValue(method = "getProfiler", at = @At("RETURN"))
    private ProfilerFiller sleep$frameProfile(ProfilerFiller p) {
        return FrameThread.drawingHere() ? FrameProfile.INSTANCE : p;
    }
}
