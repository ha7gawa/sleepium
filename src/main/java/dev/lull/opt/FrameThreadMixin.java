package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dlab.opt.FrameThreadMixin: runTick's render half recorded on the main thread and run on the frame thread (FrameThread).
 * Each call is wrapped (not redirected): the frame thread runs the recorded Operation - the whole chain of other mods'
 * wrappers and argument changes on the same call (Xaero's World Map changes render's arguments) - with its arguments.
 */
@Mixin(Minecraft.class)
public abstract class FrameThreadMixin {
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void sleep$clear(int mask, boolean checkError, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, mask, checkError);
        else op.call(mask, checkError);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void sleep$bindWrite(RenderTarget t, boolean viewport, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, t, viewport);
        else op.call(t, viewport);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;setupNoFog()V"))
    private void sleep$noFog(Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op);
        else op.call();
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableCull()V"))
    private void sleep$cull(Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op);
        else op.call();
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;fireRenderFramePre(Lnet/minecraft/client/DeltaTracker;)V", remap = false))
    private void sleep$framePre(DeltaTracker t, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, t);
        else op.call(t);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void sleep$render(GameRenderer gr, DeltaTracker t, boolean renderLevel, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, gr, t, renderLevel);
        else op.call(gr, t, renderLevel);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;fireRenderFramePost(Lnet/minecraft/client/DeltaTracker;)V", remap = false))
    private void sleep$framePost(DeltaTracker t, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, t);
        else op.call(t);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V"))
    private void sleep$unbind(RenderTarget t, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, t);
        else op.call(t);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void sleep$blit(RenderTarget t, int w, int h, Operation<Void> op) {
        if (FrameThread.recording()) FrameThread.record(op, t, w, h);
        else op.call(t, w, h);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void sleep$present(Window w, Operation<Void> op) {
        if (FrameThread.recording()) {
            FrameThread.record(op, w);
            FrameThread.present();
        } else op.call(w);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void sleep$tasksStart(boolean renderLevel, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        FrameThread.tasksStart();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
    private void sleep$tasksEnd(boolean renderLevel, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        FrameThread.tasksEnd();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$tickStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        FrameThread.tickStart();
        FrameThread.testTickLoad();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$tickEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        FrameThread.tickEnd();
    }

    /** the event loop's thread as each thread sees it: the frame thread counts as Minecraft's own while it draws */
    @Inject(method = "getRunningThread", at = @At("HEAD"), cancellable = true)
    private void sleep$frameIsSame(CallbackInfoReturnable<Thread> cir) {
        if (FrameThread.drawingHere()) cir.setReturnValue(Thread.currentThread());
    }
}
