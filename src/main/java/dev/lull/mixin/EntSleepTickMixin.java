package dev.lull.mixin;

import dev.lull.optsupport.sleep.EntitySleep;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Entity sleep: the entity's tick() in the level's entity loop goes through the engine (lab.opt.EntitySleep / diag). */
@Mixin(ServerLevel.class)
public abstract class EntSleepTickMixin {
    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void sleep$tick(Entity e) {
        if (!dev.lull.optsupport.ModProfile.ON || !dev.lull.optsupport.ModProfile.take()) {
            EntitySleep.tick(e);
            return;
        }
        long t0 = System.nanoTime();   // the Mods page's recording: this type's tick time
        EntitySleep.tick(e);
        long dt = System.nanoTime() - t0;
        dev.lull.optsupport.ModProfile.add(1, e.getType(), dt);
        dev.lull.optsupport.ModProfile.at(e.level().dimension(), e.chunkPosition().toLong(), e.getType(), dt);
    }
}
