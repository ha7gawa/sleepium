package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FrameThread: a screen set (vanilla makes it the current one before its init - the frame thread drew a PauseScreen
 * whose minecraft field was still null, 11-16, self-clocked) or the window resized (render targets remade): no frame
 * draws from the start of the change to its end (the one drawing now is waited for).
 */
@Mixin(Minecraft.class)
public abstract class FrameThreadScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void sleep$screenHold(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        FrameThread.holdForMain();
    }

    @Inject(method = "setScreen", at = @At("RETURN"))
    private void sleep$screenRelease(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
        FrameThread.releaseForMain();
    }

    @Inject(method = "resizeDisplay", at = @At("HEAD"))
    private void sleep$resizeHold(CallbackInfo ci) {
        FrameThread.holdForMain();
    }

    @Inject(method = "resizeDisplay", at = @At("RETURN"))
    private void sleep$resizeRelease(CallbackInfo ci) {
        FrameThread.releaseForMain();
    }
}
