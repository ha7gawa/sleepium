package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * part of SpecTicks, lab.fast: Entity.getInBlockState fills a lazy field and returns it with a second read - a mob
 * read from another thread (a push's isPushable filter) can lose the field to its owner's move in between and get null
 * (11-23: a NullPointerException in isLivingOnLadder). A null answer is read again; on one thread it never is null.
 */
@Mixin(Entity.class)
public abstract class ServerParallelItemsInBlockMixin {
    @Shadow public abstract Level level();

    @Shadow public abstract net.minecraft.core.BlockPos blockPosition();

    @ModifyReturnValue(method = "getInBlockState", at = @At("RETURN"))
    private BlockState sleep$inBlockNeverNull(BlockState s) {
        return s != null ? s : level().getBlockState(blockPosition());
    }
}
