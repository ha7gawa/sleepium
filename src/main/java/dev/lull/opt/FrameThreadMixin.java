package dev.lull.opt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.opt.FrameThreadMixin: runTick's render half recorded on the main thread and run on the frame thread (FrameThread). */
@Mixin(Minecraft.class)
public abstract class FrameThreadMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void sleep$clear(int mask, boolean checkError) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.CLEAR, mask, 0, checkError);
        else com.mojang.blaze3d.systems.RenderSystem.clear(mask, checkError);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void sleep$bindWrite(RenderTarget t, boolean viewport) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.BIND_WRITE, 0, 0, viewport);
        else t.bindWrite(viewport);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;setupNoFog()V"))
    private void sleep$noFog() {
        if (FrameThread.recording()) FrameThread.op(FrameThread.SETUP_NO_FOG);
        else FogRenderer.setupNoFog();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableCull()V"))
    private void sleep$cull() {
        if (FrameThread.recording()) FrameThread.op(FrameThread.ENABLE_CULL);
        else com.mojang.blaze3d.systems.RenderSystem.enableCull();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;fireRenderFramePre(Lnet/minecraft/client/DeltaTracker;)V", remap = false))
    private void sleep$framePre(DeltaTracker t) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.FRAME_PRE);
        else net.neoforged.neoforge.client.ClientHooks.fireRenderFramePre(t);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void sleep$render(GameRenderer gr, DeltaTracker t, boolean renderLevel) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.RENDER, 0, 0, renderLevel);
        else gr.render(t, renderLevel);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;fireRenderFramePost(Lnet/minecraft/client/DeltaTracker;)V", remap = false))
    private void sleep$framePost(DeltaTracker t) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.FRAME_POST);
        else net.neoforged.neoforge.client.ClientHooks.fireRenderFramePost(t);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V"))
    private void sleep$unbind(RenderTarget t) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.UNBIND_WRITE);
        else t.unbindWrite();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void sleep$blit(RenderTarget t, int w, int h) {
        if (FrameThread.recording()) FrameThread.op(FrameThread.BLIT, w, h, false);
        else t.blitToScreen(w, h);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void sleep$present(Window w) {
        if (FrameThread.recording()) {
            FrameThread.op(FrameThread.UPDATE_DISPLAY);
            FrameThread.present();
        } else w.updateDisplay();
    }

    /** the event loop's thread as each thread sees it: the frame thread counts as Minecraft's own while it draws */
    @Inject(method = "getRunningThread", at = @At("HEAD"), cancellable = true)
    private void sleep$frameIsSame(CallbackInfoReturnable<Thread> cir) {
        if (FrameThread.drawingHere()) cir.setReturnValue(Thread.currentThread());
    }
}
