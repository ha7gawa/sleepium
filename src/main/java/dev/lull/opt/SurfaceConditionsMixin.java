package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lull.optsupport.SurfaceConditions;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SurfaceConditions: a rule's condition shared with equal conditions elsewhere in the tree (once per tree build). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$TestRuleSource")
public abstract class SurfaceConditionsMixin {
    @WrapOperation(method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$ConditionSource;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object sleep$shared(SurfaceRules.ConditionSource source, Object context, Operation<Object> original) {
        return SurfaceConditions.shared(source, context, (s, c) -> original.call(s, c));
    }
}
