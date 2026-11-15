package dev.lull.opt;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.opt.RenderThreadMixin: the frame's drawing on a thread of its own (RenderThread, plan D stage A1 inverted). */
@Mixin(Minecraft.class)
public abstract class RenderThreadMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void sleep$renderThread(GameRenderer gr, DeltaTracker tracker, boolean renderLevel) {
        dev.lull.optsupport.RenderThread.render(gr, tracker, renderLevel);
    }

    /** the event loop's thread as each thread sees it: the render thread counts as Minecraft's own while it draws (isSameThread) */
    @Inject(method = "getRunningThread", at = @At("HEAD"), cancellable = true)
    private void sleep$renderIsSame(CallbackInfoReturnable<Thread> cir) {
        if (dev.lull.optsupport.RenderThread.drawingHere()) cir.setReturnValue(Thread.currentThread());
    }
}
