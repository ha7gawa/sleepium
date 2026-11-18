package dev.lull.optsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * -Dlab.opt.LiquidPosRingMixin (11-18): the neighbour positions the liquid mesher asks for (LiquidBlockRenderer:
 * pos.relative / north / above ... for every water block, ~20 a block: 16.5 MB/s of BlockPos in the zoo orbit, the
 * meshing workers' largest garbage) come from a ring of 16 mutable positions of the thread. Each is only read (block
 * state, height, light at it) before the mesher asks for more than 16 others - the deepest nesting is 3 (a corner's
 * position, its height's above()). On our pool's threads only (ThreadSlots); others allocate as before.
 */
public final class PosRing {
    private final BlockPos.MutableBlockPos[] ring = new BlockPos.MutableBlockPos[16];
    private int next;

    private PosRing() {
        for (int i = 0; i < ring.length; i++) ring[i] = new BlockPos.MutableBlockPos();
    }

    public static BlockPos at(BlockPos p, int dx, int dy, int dz) {
        Object[] s = ThreadSlots.mine();
        if (s == null) return p.offset(dx, dy, dz);
        PosRing r = (PosRing) s[ThreadSlots.POS];
        if (r == null) s[ThreadSlots.POS] = r = new PosRing();
        return r.ring[r.next++ & 15].set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
    }

    public static BlockPos at(BlockPos p, Direction d) {
        return at(p, d.getStepX(), d.getStepY(), d.getStepZ());
    }
}
