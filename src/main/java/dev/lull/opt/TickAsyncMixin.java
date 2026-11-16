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
        if (dev.lull.optsupport.FrameThread.SELF) TickAsync.joinIfDone();   // (self-clocked: woken often - never wait for the walk here)
        else TickAsync.join();
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
