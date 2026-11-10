package dev.lull.mixin;

import dev.lull.BeTickStats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ItemEntity.setItem: a hopper taking part of a stack from the ground (see BeTickStats). */
@Mixin(ItemEntity.class)
public abstract class WriteProbeItemMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void sleep$setItem(ItemStack stack, CallbackInfo ci) {
        BeTickStats.write();
        if (dev.lull.optsupport.sleep.SleepEngine.HOOKS) dev.lull.optsupport.sleep.SleepEngine.onEntity((net.minecraft.world.entity.Entity) (Object) this, "item-changed");
    }
}
