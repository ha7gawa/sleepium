package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * part of SpecTicks, lab.fast (lab.fast.pathBudget=N): at most N new paths a tick for mobs with no target (strolls,
 * a brain's walk targets); over it the path is not made this tick (the goal fails, the mob tries again later). Mobs
 * chasing a target always get theirs. 0: off, counts only.
 */
@Mixin(PathNavigation.class)
public abstract class ServerParallelItemsPathBudgetMixin {
    @Shadow @Final protected Mob mob;

    @WrapOperation(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path sleep$budget(PathFinder f, PathNavigationRegion region, Mob m, Set<BlockPos> targets, float range, int accuracy, float mult, Operation<Path> original) {
        if (dev.lull.optsupport.SpecTicks.FAST && !dev.lull.optsupport.SpecTicks.pathAllowed(mob)) return null;
        return original.call(f, region, m, targets, range, accuracy, mult);
    }
}
