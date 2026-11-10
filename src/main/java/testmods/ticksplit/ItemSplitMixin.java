package testmods.ticksplit;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import testmods.meter.ItemSplit;

/** Test only (-Dsleep.meter.tickSplit=true): ItemSplit's timers in ItemEntity.tick. */
@Mixin(ItemEntity.class)
public abstract class ItemSplitMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sleep$begin(CallbackInfo ci) {
        ItemSplit.begin((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sleep$end(CallbackInfo ci) {
        ItemSplit.end((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void sleep$moveBegin(CallbackInfo ci) {
        ItemSplit.moveBegin((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", shift = At.Shift.AFTER))
    private void sleep$moveEnd(CallbackInfo ci) {
        ItemSplit.moveEnd((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void sleep$superBegin(CallbackInfo ci) {
        ItemSplit.superBegin((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER))
    private void sleep$superEnd(CallbackInfo ci) {
        ItemSplit.superEnd((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;updateInWaterStateAndDoFluidPushing()Z"))
    private void sleep$fluidBegin(CallbackInfo ci) {
        ItemSplit.fluidBegin((ItemEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;updateInWaterStateAndDoFluidPushing()Z", shift = At.Shift.AFTER))
    private void sleep$fluidEnd(CallbackInfo ci) {
        ItemSplit.fluidEnd((ItemEntity) (Object) this);
    }
}
