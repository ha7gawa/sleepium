package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.world.entity.AnimationState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Parallel entity rendering (ParallelRender's workers) and the frame thread: HierarchicalModel's keyframe animations
 * write their scratch vector into one static Vector3f (ANIMATION_VECTOR_CACHE) - two models animated on two threads at
 * once corrupted each other's parts for a frame (the zoo's sniffer, camel ... 11-16, frame bursts). Each thread gets a
 * vector of its own. And one AnimationState's clock moved and read in one step (the tick's start between the two left
 * the frame reading a reset clock: the camel's head for a frame).
 */
@Mixin(HierarchicalModel.class)
public abstract class ParallelAnimationMixin {
    private static final ThreadLocal<org.joml.Vector3f> SLEEP$CACHE = ThreadLocal.withInitial(org.joml.Vector3f::new);

    @Redirect(method = {"animateWalk(Lnet/minecraft/client/animation/AnimationDefinition;FFFF)V", "applyStatic(Lnet/minecraft/client/animation/AnimationDefinition;)V"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/model/HierarchicalModel;ANIMATION_VECTOR_CACHE:Lorg/joml/Vector3f;", opcode = Opcodes.GETSTATIC))
    private org.joml.Vector3f sleep$cache() {
        return SLEEP$CACHE.get();
    }

    @Redirect(method = {"lambda$animate$2", "method_43783"}, require = 0,
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/model/HierarchicalModel;ANIMATION_VECTOR_CACHE:Lorg/joml/Vector3f;", opcode = Opcodes.GETSTATIC))
    private org.joml.Vector3f sleep$cacheAnimate() {
        return SLEEP$CACHE.get();
    }

    @WrapMethod(method = "animate(Lnet/minecraft/world/entity/AnimationState;Lnet/minecraft/client/animation/AnimationDefinition;FF)V")
    private void sleep$animateLocked(AnimationState state, AnimationDefinition def, float age, float speed, Operation<Void> original) {
        synchronized (state) {
            original.call(state, def, age, speed);
        }
    }
}
