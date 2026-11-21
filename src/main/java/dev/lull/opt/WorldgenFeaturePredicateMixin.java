package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * WorldgenFeatureAlloc (predicates): StateTestingPredicate.test is {@code test(level.getBlockState(pos.offset(offset)))}.
 * Most predicates have a zero offset, and placement hands them EnvironmentScan's MutableBlockPos: MutableBlockPos.offset
 * returns {@code super.offset(...).immutable()} - a copy of the position just to read one block state (~10 % of the
 * FEATURES allocation left after the placement walk). With a zero offset the position itself goes to getBlockState:
 * the same coordinates, read once and not kept (getBlockState only reads them, as for every MutableBlockPos vanilla
 * passes it). Non-zero offsets: vanilla's offset.
 */
@Mixin(StateTestingPredicate.class)
public abstract class WorldgenFeaturePredicateMixin {
    @Redirect(method = "test(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;offset(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos sleep$offset(BlockPos pos, Vec3i offset) {
        return offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0 ? pos : pos.offset(offset);
    }
}
