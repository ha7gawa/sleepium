package dev.lull.opt;

import dev.lull.optsupport.RegionStates;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** RegionStates light box: an AO face's light and shade reads from the mesh's box (see RegionStates.LIGHT). */
@Mixin(ModelBlockRenderer.AmbientOcclusionFace.class)
public abstract class LightBoxMixin {
    @Redirect(method = "calculate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer$Cache;getLightColor(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int sleep$light(ModelBlockRenderer.Cache cache, BlockState state, BlockAndTintGetter level, BlockPos pos) {
        if (RegionStates.LIGHT) {
            RegionStates rs = RegionStates.of(level);
            if (rs != null) {
                int i = rs.index(pos);
                if (i >= 0) return rs.light(i, level, state, pos);
            }
        }
        return cache.getLightColor(state, level, pos);
    }

    @Redirect(method = "calculate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer$Cache;getShadeBrightness(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float sleep$shade(ModelBlockRenderer.Cache cache, BlockState state, BlockAndTintGetter level, BlockPos pos) {
        if (RegionStates.LIGHT) {
            RegionStates rs = RegionStates.of(level);
            if (rs != null) {
                int i = rs.index(pos);
                if (i >= 0) return rs.shade(i, level, state, pos);
            }
        }
        return cache.getShadeBrightness(state, level, pos);
    }
}
