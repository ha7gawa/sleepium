package dev.lull.dhmixin;

import dev.lull.optsupport.FrameThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons hands its GL work (LOD buffer uploads, its render-thread task queue) to Minecraft.execute - in vanilla
 * the main thread is the render thread. With the frame thread the context and DH's remembered render thread are the
 * frame thread's, the main thread runs the queued tasks: DH refused them ("tried to create a GLBuffer outside the MC
 * render thread", the LODs never uploaded). Its render-thread tasks go to the frame thread's own queue instead.
 */
// (the NeoForge half of DH's fabric-neoforge jar: its classes carry the suffix _neoforge)
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper_neoforge", remap = false)
public abstract class DhRenderTasksMixin {
    @Inject(method = "executeOnRenderThread", at = @At("HEAD"), cancellable = true, require = 0)
    private void sleep$toFrameThread(Runnable task, CallbackInfo ci) {
        if (FrameThread.takeRenderTask(task)) ci.cancel();
    }
}
