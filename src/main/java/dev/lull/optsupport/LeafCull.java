package dev.lull.optsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * -Dlab.leaves.cull=N (non-exact; 1.3.0 research): leaves draw no face deep inside a canopy. Vanilla draws all six
 * faces of every leaf block (leaves don't occlude), so a forest is mostly faces between leaves - on the N100 the
 * cutout_mipped layer (leaves, grass) is 1.6 of a 3.3-ms GPU frame, bound by geometry rather than the alpha test.
 * A leaf block's face toward a neighbour is dropped when the neighbour and the next N-1 blocks beyond it in that
 * direction are leaves (any block in the #leaves tag, so mods' leaves too): from outside the canopy N layers stay
 * visible through the holes; deeper faces only show from inside a tree. N=1 drops every face between leaves. With
 * N > 1 a change of leaves also marks the sections N blocks away (LeafCullDirtyMixin). Host forest view: depth 2
 * differs from vanilla in 562 of 409,920 pixels, cutout_mipped GPU time -27 % (depth 1: 1238 pixels, -48 %).
 */
public final class LeafCull {
    private LeafCull() {}

    public static final int DEPTH = Integer.getInteger("lab.leaves.cull", 0);
    public static long culled;
    public static final Direction[] DIRS = Direction.values();

    /** Block.shouldRenderFace's answer for state at offset, its face toward pos (vanilla said draw) */
    public static boolean draw(BlockState state, BlockGetter level, Direction face, BlockPos pos, BlockState neighbour) {
        if (!state.is(BlockTags.LEAVES) || !neighbour.is(BlockTags.LEAVES)) return true;
        if (DEPTH > 1) {
            BlockPos.MutableBlockPos p = pos.mutable();
            for (int i = 1; i < DEPTH; i++) {
                p.move(face);
                if (!level.getBlockState(p).is(BlockTags.LEAVES)) return true;
            }
        }
        culled++;
        return false;
    }
}
