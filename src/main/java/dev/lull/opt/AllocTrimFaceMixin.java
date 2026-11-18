package dev.lull.opt;

import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * AllocTrim: Block.shouldRenderFace's occlusion cache keyed by a long (state id, neighbour state id, face) instead of
 * a new BlockStatePairKey per call (3.1 % of all allocation during teleports: every face of every meshed block).
 * The key equals vanilla's (identity of both states and the face), the cache is the same per-thread LRU of 2048 with
 * the same operations in the same order, so it holds the same entries and every answer is vanilla's - including where
 * vanilla's cache ignores the position.
 */
@Mixin(Block.class)
public abstract class AllocTrimFaceMixin {
    /**
     * @author probe (AllocTrim)
     * @reason the occlusion cache's key without an allocation
     */
    @Overwrite
    public static boolean shouldRenderFace(BlockState state, BlockGetter level, BlockPos offset, Direction face, BlockPos pos) {
        BlockState blockstate = level.getBlockState(pos);
        if (state.skipRendering(blockstate, face)) {
            return false;
        } else if (blockstate.hidesNeighborFace(level, pos, state, face.getOpposite()) && state.supportsExternalFaceHiding()) {
            return false;
        } else if (blockstate.canOcclude()) {
            long key = ((long) dev.lull.optsupport.AllocTrim.id(state) << 32) | ((long) dev.lull.optsupport.AllocTrim.id(blockstate) << 3) | face.ordinal();
            Long2ByteLinkedOpenHashMap map = dev.lull.optsupport.AllocTrim.faces();
            byte b0 = map.getAndMoveToFirst(key);
            if (b0 != 127) {
                return b0 != 0;
            } else {
                VoxelShape voxelshape = state.getFaceOcclusionShape(level, offset, face);
                if (voxelshape.isEmpty()) {
                    return true;
                } else {
                    VoxelShape voxelshape1 = blockstate.getFaceOcclusionShape(level, pos, face.getOpposite());
                    boolean flag = Shapes.joinIsNotEmpty(voxelshape, voxelshape1, BooleanOp.ONLY_FIRST);
                    if (map.size() == 2048) {
                        map.removeLastByte();
                    }
                    map.putAndMoveToFirst(key, (byte) (flag ? 1 : 0));
                    return flag;
                }
            }
        } else {
            return true;
        }
    }
}
