package testmods.ticksplit;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import testmods.meter.EntityCensus;

/** Test only (-Dsleep.meter.tickSplit=true): the client's entity walk for EntityCensus. */
@Mixin(ClientLevel.class)
public abstract class CensusWalkMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void sleep$walkIn(Entity e, CallbackInfo ci) {
        EntityCensus.walk(e, true);
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void sleep$walkOut(Entity e, CallbackInfo ci) {
        EntityCensus.walk(e, false);
    }
}
