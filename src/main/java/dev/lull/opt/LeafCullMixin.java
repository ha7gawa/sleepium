package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.lull.optsupport.LeafCull;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** -Dlab.leaves.cull=N (lab.opt.LeafCullMixin): no faces deep inside a canopy (LeafCull). After AllocTrim's overwrite. */
@Mixin(value = Block.class, priority = 1100)
public abstract class LeafCullMixin {
    @ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
    private static boolean sleep$leafCull(boolean draw, @Local(argsOnly = true) BlockState state, @Local(argsOnly = true) BlockGetter level,
            @Local(argsOnly = true) Direction face, @Local(argsOnly = true, ordinal = 1) BlockPos pos) {
        if (!draw) return false;
        return LeafCull.draw(state, level, face, pos, level.getBlockState(pos));
    }
}
