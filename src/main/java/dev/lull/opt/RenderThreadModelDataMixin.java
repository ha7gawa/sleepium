package dev.lull.opt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dlab.opt.RenderThreadMixin: NeoForge's ModelDataManager checks its owning thread (the main thread, which made the
 * level); the render thread drawing a frame stands in for it (stage A1: the main thread waits meanwhile).
 */
@Mixin(targets = "net.neoforged.neoforge.client.model.data.ModelDataManager", remap = false)
public abstract class RenderThreadModelDataMixin {
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"), require = 0)
    private Thread sleep$owner() {
        return dev.lull.optsupport.RenderThread.ownerView();
    }
}
