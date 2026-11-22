package dev.lull.opt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** part of SpecTicks (lab): a speculative tick must not write the world or spawn - it stops there and runs again in order. */
@Mixin(ServerLevel.class)
public abstract class ServerParallelItemsAbortMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void sleep$specSpawn(Entity e, CallbackInfoReturnable<Boolean> cir) {
        if (dev.lull.optsupport.SpecTicks.EXACT && dev.lull.optsupport.sleep.WB.TX) dev.lull.optsupport.SpecTicks.abortIfSpeculative("spawns " + e.getType().toShortString());
    }
}
