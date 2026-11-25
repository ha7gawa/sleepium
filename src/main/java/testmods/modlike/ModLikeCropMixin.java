package testmods.modlike;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stand-in for a mod's mixin into a vanilla block's random tick (seasons, growth mods) with state in a plain HashMap. */
@Mixin(CropBlock.class)
public abstract class ModLikeCropMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void modlike$crop(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        testmods.modlikestate.ModLikeState.crop(pos.asLong());
    }
}
