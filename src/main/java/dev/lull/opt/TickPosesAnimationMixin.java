package dev.lull.opt;

import net.minecraft.world.entity.AnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TickPoses: a model's setupAnim moves its entity's AnimationState clock (updateTime) - a render that writes the
 * entity's state. Seen during a pose capture on a worker, the type is left to per-frame drawing (TickPoses.renderWrites):
 * the capture and the frame's own render of the entity moved the same clock at once (the zoo's sniffer turned its head
 * for one frame, 11-16).
 */
@Mixin(AnimationState.class)
public abstract class TickPosesAnimationMixin {
    @Inject(method = "updateTime", at = @At("HEAD"))
    private void sleep$write(float ageInTicks, float speed, CallbackInfo ci) {
        dev.lull.optsupport.TickPoses.renderWrites();
    }
}
