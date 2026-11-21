package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.SurfacePlan;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SurfacePlan: the chunk's root rule gets a plan for its first tests (once per chunk; see SurfacePlan). */
@Mixin(SurfaceSystem.class)
public abstract class SurfacePlanMixin {
    @WrapOperation(method = "buildSurface", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object sleep$plan(SurfaceRules.RuleSource source, Object context, Operation<Object> original) {
        Object rule = original.call(source, context);
        SurfacePlan.of(source, context, rule);
        return rule;
    }
}
