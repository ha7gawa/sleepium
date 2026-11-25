package dev.lull.opt;

import dev.lull.optsupport.MapAllMemo;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * lab.opt.WorldgenMapMemoMixin: every child a vanilla density function maps in its mapAll goes through MapAllMemo, so a
 * node instance shared by several parents is mapped once per NoiseChunk traversal (see MapAllMemo for why that is the
 * same tree). A redirect, not a wrapper: no object per call.
 */
@Mixin(targets = {"net.minecraft.world.level.levelgen.DensityFunctions$Ap2", "net.minecraft.world.level.levelgen.DensityFunctions$BlendDensity",
        "net.minecraft.world.level.levelgen.DensityFunctions$Clamp", "net.minecraft.world.level.levelgen.DensityFunctions$Mapped",
        "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd", "net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice",
        "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise", "net.minecraft.world.level.levelgen.DensityFunctions$WeirdScaledSampler",
        "net.minecraft.world.level.levelgen.NoiseRouter"})
public abstract class WorldgenMapMemoMixin {
    @Redirect(method = {"mapAll", "method_41544", "method_41080"}, at = @At(value = "INVOKE",   // (Fabric production: the build names "mapAll" after DensityFunction's for every target; NoiseRouter's is method_41544, Mapped's covariant one method_41080)
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction sleep$memo(DensityFunction child, DensityFunction.Visitor visitor) {
        return MapAllMemo.map(child, visitor);
    }
}
