package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FrameThread, lab.frameThread.publish (notes/75 stage 1): the client level's entity set changed (an entity began or
 * ended being tracked: spawned, removed, its chunk loaded or unloaded) - the list frames draw is published again at the
 * next hand-over instead of being copied at every one.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks")
public abstract class FrameThreadTrackingMixin {
    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void sleep$started(net.minecraft.world.entity.Entity e, CallbackInfo ci) {
        FrameThread.entitiesChanged();
    }

    @Inject(method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void sleep$ended(net.minecraft.world.entity.Entity e, CallbackInfo ci) {
        FrameThread.entitiesChanged();
    }
}
