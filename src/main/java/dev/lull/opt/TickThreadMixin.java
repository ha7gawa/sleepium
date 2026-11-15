package dev.lull.opt;

import dev.lull.optsupport.TickThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.opt.TickThreadMixin: the client tick on its own thread (TickThread, plan D stage A1). */
@Mixin(Minecraft.class)
public abstract class TickThreadMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
    private void sleep$tickThread(Minecraft mc) {
        TickThread.tick(mc);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void sleep$texturesLater(TextureManager tm) {
        if (!TickThread.deferTextures()) tm.tick();
    }

    @Inject(method = "getRunningThread", at = @At("HEAD"), cancellable = true)
    private void sleep$runningThread(CallbackInfoReturnable<Thread> cir) {
        Thread t = TickThread.runningThread();
        if (t != null) cir.setReturnValue(t);
    }
}
