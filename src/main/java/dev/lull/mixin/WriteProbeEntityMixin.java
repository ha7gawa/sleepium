package dev.lull.mixin;

import dev.lull.BeTickStats;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity-side world-visible writes for the idle-tick detector (see {@link BeTickStats}). */
@Mixin(Entity.class)
public abstract class WriteProbeEntityMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void sleep$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        BeTickStats.write();
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.onEntity((Entity) (Object) this, "entity-removed");
    }
}
