package dev.lull.opt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OnePool: the render thread is the client's side (Sides). Part of lab.opt.OnePool. */
@Mixin(Minecraft.class)
public abstract class SidesRenderThreadMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$renderThread(CallbackInfo ci) {
        dev.lull.optsupport.Sides.renderThread = Thread.currentThread();
    }
}
