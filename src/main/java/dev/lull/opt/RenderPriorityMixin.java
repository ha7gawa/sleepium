package dev.lull.opt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/render-priority (client): the render thread (and the singleplayer server's) above the game's other threads, so a
 * frame does not wait for its core behind background work - on a 4-core PC (Intel N100) stutters 7.9 -> 1.3 %, the
 * slowest frames 50 -> 21 ms. RenderPriority.
 */
@Mixin(Minecraft.class)
public abstract class RenderPriorityMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void sleep$priority(boolean renderLevel, CallbackInfo ci) {
        dev.lull.optsupport.RenderPriority.render();
    }
}
