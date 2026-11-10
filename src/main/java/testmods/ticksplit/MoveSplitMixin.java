package testmods.ticksplit;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import testmods.meter.ItemSplit;

/** Test only (-Dsleep.meter.tickSplit=true): Entity.move of the client's items timed in parts (ItemSplit). */
@Mixin(Entity.class)
public abstract class MoveSplitMixin {
    @Inject(method = "move", at = @At("HEAD"))
    private void sleep$mvBegin(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 0, true);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void sleep$mvEnd(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 0, false);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private void sleep$colBegin(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 1, true);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", shift = At.Shift.AFTER))
    private void sleep$colEnd(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 1, false);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V"))
    private void sleep$gBegin(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 2, true);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V", shift = At.Shift.AFTER))
    private void sleep$gEnd(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 2, false);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tryCheckInsideBlocks()V"))
    private void sleep$inBegin(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 3, true);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tryCheckInsideBlocks()V", shift = At.Shift.AFTER))
    private void sleep$inEnd(MoverType type, Vec3 pos, CallbackInfo ci) {
        ItemSplit.part((Entity) (Object) this, 3, false);
    }
}
