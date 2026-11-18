package dev.lull.optsupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * -Dlab.opt.RegionStatesMixin (11-18): while a section is meshed, the block states read from its region inside the
 * 18 x 18 x 18 box around it are kept for that mesh. A mesh reads every block many times (its own faces, its
 * neighbours' AO corners, face culling, fluids): each read unpacked the palette copy again (PalettedContainer.get: 13.7 %
 * of the meshing workers in flight). The region holds copies made before the mesh began - they do not change while it
 * runs - so a kept state is the state a read would give. On our pool's threads only (ThreadSlots), stamped by a
 * generation (nothing cleared between meshes); reads outside the box, and other threads, read as before.
 */
public final class RegionStates {
    private static final int N = 18;
    private Object region;
    private int x0, y0, z0, gen;
    private final BlockState[] states = new BlockState[N * N * N];
    private final int[] stamp = new int[N * N * N];

    public static void begin(Object region, SectionPos section) {
        Object[] s = ThreadSlots.mine();
        if (s == null) return;
        RegionStates rs = (RegionStates) s[ThreadSlots.STATES];
        if (rs == null) s[ThreadSlots.STATES] = rs = new RegionStates();
        if (++rs.gen == 0) {
            java.util.Arrays.fill(rs.stamp, 0);
            rs.gen = 1;
        }
        rs.region = region;
        rs.x0 = section.minBlockX() - 1;
        rs.y0 = section.minBlockY() - 1;
        rs.z0 = section.minBlockZ() - 1;
    }

    public static void end() {
        Object[] s = ThreadSlots.mine();
        if (s == null) return;
        RegionStates rs = (RegionStates) s[ThreadSlots.STATES];
        if (rs != null) rs.region = null;
    }

    /** the box's kept state for (region, pos), or null: then read it and hand it to keep() */
    public static RegionStates of(Object region) {
        Object[] s = ThreadSlots.mine();
        if (s == null) return null;
        RegionStates rs = (RegionStates) s[ThreadSlots.STATES];
        return rs != null && rs.region == region ? rs : null;
    }

    /** the index of pos in the box, or -1 */
    public int index(BlockPos pos) {
        int dx = pos.getX() - x0, dy = pos.getY() - y0, dz = pos.getZ() - z0;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= N || dy >= N || dz >= N) return -1;
        return (dy * N + dz) * N + dx;
    }

    public BlockState kept(int i) {
        return stamp[i] == gen ? states[i] : null;
    }

    public void keep(int i, BlockState s) {
        states[i] = s;
        stamp[i] = gen;
    }
}
