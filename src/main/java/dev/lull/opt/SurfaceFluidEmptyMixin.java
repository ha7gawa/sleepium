package dev.lull.opt;

import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SurfaceFluidEmpty: the surface step asks every block of every column (twice: its scan for the stone below, its main
 * loop) whether its fluid state is empty - FluidState.isEmpty is getType().isEmpty(), a virtual call through two more
 * objects. Every block without a fluid shares Fluids.EMPTY's one default state, whose answer is true: compared by
 * identity first, the call only for the others (same answer for every state).
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceFluidEmptyMixin {
    @Unique private static final FluidState SLEEP$EMPTY = Fluids.EMPTY.defaultFluidState();

    @Redirect(method = {"buildSurface", "isStone"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"))
    private boolean sleep$empty(FluidState state) {
        return state == SLEEP$EMPTY || state.isEmpty();
    }
}
