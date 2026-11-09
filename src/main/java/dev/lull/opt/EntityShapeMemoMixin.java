package dev.lull.opt;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/particle-shapes (client): the client's entity tick is a window for CollideKernel's per-position shape memo too
 * (the level does not change during it: packets and block placing are handled outside it); context-free blocks only.
 */
@Mixin(ClientLevel.class)
public abstract class EntityShapeMemoMixin {
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void sleep$start(CallbackInfo ci) {
        dev.lull.optsupport.CollideKernel.entityTick(true);
    }

    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void sleep$end(CallbackInfo ci) {
        dev.lull.optsupport.CollideKernel.entityTick(false);
    }
}
