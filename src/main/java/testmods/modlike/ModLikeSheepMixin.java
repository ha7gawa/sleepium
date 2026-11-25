package testmods.modlike;

import net.minecraft.world.entity.animal.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stand-in for a mod's mixin into a vanilla mob's tick that keeps state of its own in a plain HashMap (not for threads). */
@Mixin(Sheep.class)
public abstract class ModLikeSheepMixin {
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void modlike$sheep(CallbackInfo ci) {
        if (!((Sheep) (Object) this).level().isClientSide) testmods.modlikestate.ModLikeState.sheep(((Sheep) (Object) this).getId());
    }
}
