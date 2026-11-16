package dev.lull.opt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AllocTrim: FlowingFluid.getNewLiquid's neighbour positions reused where nothing can keep them (AllocTrimFluid). */
@Mixin(FlowingFluid.class)
public abstract class AllocTrimFluidMixin {
    @Redirect(method = "getNewLiquid", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos sleep$neighbour(BlockPos pos, Direction d) {
        return dev.lull.optsupport.AllocTrimFluid.neighbour((FlowingFluid) (Object) this, pos, d);
    }
}
