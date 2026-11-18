package dev.lull.opt;

import dev.lull.optsupport.RegionStates;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** RegionStates: a meshing region's block reads inside the mesh's box kept for the mesh (see there). */
@Mixin(RenderChunkRegion.class)
public abstract class RegionStatesMixin {
    @Redirect(method = {"getBlockState", "getFluidState"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState sleep$state(RenderChunk chunk, BlockPos pos) {
        RegionStates rs = RegionStates.of(this);
        if (rs == null) return chunk.getBlockState(pos);
        int i = rs.index(pos);
        if (i < 0) return chunk.getBlockState(pos);
        BlockState s = rs.kept(i);
        if (s == null) {
            s = chunk.getBlockState(pos);
            rs.keep(i, s);
        }
        return s;
    }
}
