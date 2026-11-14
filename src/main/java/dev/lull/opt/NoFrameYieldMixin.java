package dev.lull.opt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dlab.opt.NoFrameYield=true (lab, 11-15): no Thread.yield() at the end of each frame. On Windows it is SwitchToThread:
 * the render thread hands its core to any ready thread on that core and may wait for it - the N100 traces show the
 * render thread off the CPU in WrYieldExecution for tens of ms (once 172 ms) while JIT, server and pool threads ran.
 * The render thread is above normal priority (RenderPriority); the yield gives that away once a frame.
 */
@Mixin(Minecraft.class)
public abstract class NoFrameYieldMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"), require = 0)
    private void sleep$noYield() {
    }
}
