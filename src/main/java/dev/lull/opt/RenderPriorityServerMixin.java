package dev.lull.opt;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RenderPriorityMixin: the singleplayer server's thread above normal with the render thread (RenderPriority). */
@Mixin(IntegratedServer.class)
public abstract class RenderPriorityServerMixin {
    @Inject(method = "initServer", at = @At("HEAD"))
    private void sleep$priority(CallbackInfoReturnable<Boolean> cir) {
        dev.lull.optsupport.RenderPriority.server();
    }
}
