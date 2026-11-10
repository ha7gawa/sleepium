package dev.lull.mixin;

import dev.lull.optsupport.sleep.EntitySleep;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Entity sleep on the client (-Dlab.opt.ClientEntitySleep): the client's entity loop through the engine, its own side. */
@Mixin(ClientLevel.class)
public abstract class ClientEntSleepTickMixin {
    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void sleep$tick(Entity e) {
        if (EntitySleep.CLIENT_ON) EntitySleep.tick(e);
        else e.tick();
    }
}
