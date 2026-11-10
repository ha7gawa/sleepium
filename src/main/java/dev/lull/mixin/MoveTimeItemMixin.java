package dev.lull.mixin;

import dev.lull.optsupport.MoveTime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dsleep.moveTime=true): MoveTime's tick root for dropped items (not living: no LivingEntity.tick). */
@Mixin(ItemEntity.class)
public abstract class MoveTimeItemMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$tickIn(CallbackInfo ci) { MoveTime.enter(MoveTime.TICK, (Entity) (Object) this); }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$tickOut(CallbackInfo ci) { MoveTime.exit((Entity) (Object) this); }
}
