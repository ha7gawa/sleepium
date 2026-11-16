package dev.lull.opt;

import net.minecraft.client.model.HierarchicalModel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Parallel entity rendering (ParallelRender's workers): HierarchicalModel's keyframe animations write their scratch
 * vector into one static Vector3f (ANIMATION_VECTOR_CACHE) - two models animated on two threads at once corrupted each
 * other's parts for a frame (1.21.1, the zoo's sniffer and camel, 11-16, frame bursts). Each thread gets a vector of
 * its own: every read of the field in the class (animateWalk, applyStatic, animate's lambda - a wildcard, as the
 * lambda's name differs under production's SRG names).
 */
@Mixin(HierarchicalModel.class)
public abstract class ParallelAnimationMixin {
    private static final ThreadLocal<org.joml.Vector3f> SLEEP$CACHE = ThreadLocal.withInitial(org.joml.Vector3f::new);

    @Redirect(method = "*", require = 1, allow = 3,
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/model/HierarchicalModel;ANIMATION_VECTOR_CACHE:Lorg/joml/Vector3f;", opcode = Opcodes.GETSTATIC))
    private org.joml.Vector3f sleep$cache() {
        return SLEEP$CACHE.get();
    }
}
