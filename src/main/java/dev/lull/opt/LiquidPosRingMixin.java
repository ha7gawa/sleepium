package dev.lull.opt;

import dev.lull.optsupport.PosRing;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** PosRing: the liquid mesher's neighbour positions from the thread's ring (see there). */
@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidPosRingMixin {
    private static final String M = "tesselate";

    @Redirect(method = {"tesselate", "getHeight(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
            "getLightColor", "isFaceOccludedByNeighbor"}, require = 2,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$relative(BlockPos p, Direction d) {
        return PosRing.at(p, d);
    }

    @Redirect(method = {"tesselate", "getHeight(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
            "getLightColor"}, require = 2,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$above(BlockPos p) {
        return PosRing.at(p, 0, 1, 0);
    }

    @Redirect(method = M, require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$below(BlockPos p) {
        return PosRing.at(p, 0, -1, 0);
    }

    @Redirect(method = M, require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;north()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$north(BlockPos p) {
        return PosRing.at(p, 0, 0, -1);
    }

    @Redirect(method = M, require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;south()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$south(BlockPos p) {
        return PosRing.at(p, 0, 0, 1);
    }

    @Redirect(method = M, require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;east()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$east(BlockPos p) {
        return PosRing.at(p, 1, 0, 0);
    }

    @Redirect(method = M, require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;west()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos sleep$west(BlockPos p) {
        return PosRing.at(p, -1, 0, 0);
    }
}
