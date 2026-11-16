package dev.lull.opt;

import dev.lull.optsupport.TickAsync;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TickAsync: the walk joined at a frame's / tick's start, started when the client tick returns. */
@Mixin(Minecraft.class)
public abstract class TickAsyncMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void sleep$joinAtFrame(boolean renderLevel, CallbackInfo ci) {
        if (dev.lull.optsupport.FrameThread.active) TickAsync.joinIfDone();   // (self-clocked: woken often - never wait for the walk here)
        else TickAsync.join();
    }

    /**
     * The client's tasks (packets above all) never run while the walk ticks entities on the pool - as in vanilla, where a
     * packet never lands in the middle of an entity's tick. A strider's own tick sets its suffocating speed modifier
     * while the main thread applied an attribute packet to it (remove all, add the server's): "Modifier is already
     * applied" (1.20.1 zoo, decoupled, 11-17; any mob whose tick writes what a packet writes). With the walk still
     * running: joined first if a tick is due in this runTick (its start joins anyway), else the tasks wait for the next
     * runTick (the walk's end wakes the main thread: FrameThread.walkDone).
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void sleep$tasksAfterWalk(Minecraft mc, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> op,
            @com.llamalad7.mixinextras.sugar.Local(ordinal = 0) int ticksDue) {
        if (!TickAsync.tasksMayRun(ticksDue > 0)) return;
        op.call(mc);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$joinAtTick(CallbackInfo ci) {
        TickAsync.join();   // (two ticks in one runTick: the first one's walk done before the second begins)
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$startAtTickEnd(CallbackInfo ci) {
        TickAsync.tickEnd();
    }
}
