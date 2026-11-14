package dev.lull.opt;

import dev.lull.optsupport.TickSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TickSlice: an entity ticked (a frame may come next); riders ticked by their vehicle counted too. */
@Mixin(ClientLevel.class)
public abstract class TickSliceLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void sleep$ticked(Entity entity, CallbackInfo ci) {
        TickSlice.afterEntity(entity);
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"))
    private void sleep$rider(Entity mount, Entity rider, CallbackInfo ci) {
        TickSlice.ticked(rider);
    }
}
