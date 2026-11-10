package dev.lull.mixin;

import dev.lull.BeTickStats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** LivingEntity.addEffect: beacons and conduits applying effects (see BeTickStats). */
@Mixin(LivingEntity.class)
public abstract class WriteProbeLivingMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"))
    private void sleep$addEffect(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> cir) {
        BeTickStats.write();
    }
}
