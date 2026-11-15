package dev.lull.opt;

import dev.lull.optsupport.LeafCull;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LeafCull with depth N > 1: a leaf block's face depends on the blocks up to N away along its direction, vanilla marks
 * the sections within one block of a change - a change of leaves also marks the sections N blocks away along the axes.
 */
@Mixin(LevelRenderer.class)
public abstract class LeafCullDirtyMixin {
    @Shadow protected abstract void setSectionDirty(int x, int y, int z, boolean reRenderOnMainThread);

    @Inject(method = "blockChanged", at = @At("TAIL"))
    private void sleep$leafReach(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (LeafCull.DEPTH < 2 || !oldState.is(BlockTags.LEAVES) && !newState.is(BlockTags.LEAVES)) return;
        boolean main = (flags & 8) != 0;
        for (Direction d : LeafCull.DIRS) {
            for (int i = 2; i <= LeafCull.DEPTH; i++) {
                int x = pos.getX() + d.getStepX() * i, y = pos.getY() + d.getStepY() * i, z = pos.getZ() + d.getStepZ() * i;
                setSectionDirty(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z), main);
            }
        }
    }
}
