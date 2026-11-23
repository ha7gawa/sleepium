package dev.lull.opt;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * part of SpecTicks, lab.fast: two mobs on two threads could both pick up one item (a copy). A run tick's pickup is
 * kept for the run's end, in walk order, and done only if the item is still there then.
 */
@Mixin(Mob.class)
public abstract class ServerParallelItemsPickupMixin {
    @Shadow
    protected abstract void pickUpItem(ItemEntity item);

    @Inject(method = "pickUpItem", at = @At("HEAD"), cancellable = true)
    private void sleep$fastPickup(ItemEntity item, CallbackInfo ci) {
        if (!dev.lull.optsupport.SpecTicks.FAST) return;
        if (dev.lull.optsupport.SpecTicks.deferWorld(() -> {
            if (!item.isRemoved() && !item.getItem().isEmpty()) pickUpItem(item);
        })) ci.cancel();
    }
}
