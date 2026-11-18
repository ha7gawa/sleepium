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
            java.util.Arrays.fill(rs.lstamp, 0);
            java.util.Arrays.fill(rs.sstamp, 0);
            rs.gen = 1;
        }
        rs.region = region;
        rs.x0 = section.minBlockX() - 1;
        rs.y0 = section.minBlockY() - 1;
        rs.z0 = section.minBlockZ() - 1;
        if (TIME) rs.t0 = BEAN.getCurrentThreadCpuTime();
        if (FILL) rs.fill(region, section);
    }

    /**
     * -Dlab.regionStates.fill (11-18): the section's own 16^3 decoded into the box in one pass at the start (the
     * container RenderChunk.getBlockState reads, unpacked cell by cell as SimpleBitStorage.get does, each id through the
     * palette as PalettedContainer.get does) instead of a get() a block on the first read; the one-block border from the
     * neighbour sections stays read on demand. 13.9 % of the meshing workers were block reads after Enclosed.
     */
    public static final boolean FILL = Boolean.getBoolean("lab.regionStates.fill");
    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    public static long filled, notFilled;

    @SuppressWarnings("unchecked")
    private void fill(Object region, SectionPos section) {
        Object[] chunks = ((net.minecraft.client.renderer.chunk.RenderChunkRegion) region).chunks;
        if (chunks == null || chunks.length != 9 || !(chunks[4] instanceof LazySections.Chunk c) || !c.sleep$fillable()) {
            notFilled++;
            return;
        }
        net.minecraft.world.level.chunk.PalettedContainer<BlockState> pc = c.sleep$sectionAt(section.minBlockY());
        int g = gen;
        if (pc == null) {
            for (int y = 1; y <= 16; y++)
                for (int z = 1; z <= 16; z++) {
                    int b = (y * N + z) * N + 1;
                    for (int x = 0; x < 16; x++) {
                        states[b + x] = AIR;
                        stamp[b + x] = g;
                    }
                }
            filled++;
            return;
        }
        var data = pc.data;
        net.minecraft.util.BitStorage st = data.storage();
        net.minecraft.world.level.chunk.Palette<BlockState> pal = data.palette();
        int bits = st.getBits();
        if (bits == 0) {   // one value
            BlockState v = pal.valueFor(0);
            for (int y = 1; y <= 16; y++)
                for (int z = 1; z <= 16; z++) {
                    int b = (y * N + z) * N + 1;
                    for (int x = 0; x < 16; x++) {
                        states[b + x] = v;
                        stamp[b + x] = g;
                    }
                }
            filled++;
            return;
        }
        if (!(st instanceof net.minecraft.util.SimpleBitStorage) || st.getSize() != 4096) {
            notFilled++;
            return;
        }
        long[] raw = st.getRaw();
        int per = 64 / bits;
        long mask = (1L << bits) - 1L;
        int i = 0;
        for (int cell = 0; i < 4096; cell++) {
            long w = raw[cell];
            for (int k = 0; k < per && i < 4096; k++, i++) {
                int id = (int) (w & mask);
                w >>>= bits;
                int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                int b = ((y + 1) * N + (z + 1)) * N + (x + 1);
                states[b] = pal.valueFor(id);
                stamp[b] = g;
            }
        }
        filled++;
    }

    public static void end() {
        Object[] s = ThreadSlots.mine();
        if (s == null) return;
        RegionStates rs = (RegionStates) s[ThreadSlots.STATES];
        if (rs != null) {
            rs.region = null;
            if (TIME && rs.t0 != 0) {
                MESH_NS.add(BEAN.getCurrentThreadCpuTime() - rs.t0);
                MESHES.increment();
                rs.t0 = 0;
            }
        }
    }

    /** -Dlab.meshTime (11-18): thread CPU time a section compile on our pool's threads, summed (for a mesher's cost a section) */
    public static final boolean TIME = Boolean.getBoolean("lab.meshTime");
    private static final java.lang.management.ThreadMXBean BEAN = java.lang.management.ManagementFactory.getThreadMXBean();
    private static final java.util.concurrent.atomic.LongAdder MESH_NS = new java.util.concurrent.atomic.LongAdder(), MESHES = new java.util.concurrent.atomic.LongAdder();
    private long t0;

    public static String timeReport() {
        long n = MESHES.sum();
        return String.format(java.util.Locale.ROOT, "meshes %d, %.1f us CPU each", n, n == 0 ? 0.0 : MESH_NS.sum() / 1000.0 / n);
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

    /**
     * -Dlab.lightBox (11-18, with lab.opt.LightBoxMixin): the AO light reads of a mesh (ModelBlockRenderer.Cache's
     * getLightColor / getShadeBrightness: vanilla keeps the last 100 positions of the compile, by position) kept for the
     * whole box - each position's value computed once, the same call vanilla's cache makes on a miss. (Vanilla reads
     * the live light engine while it meshes: where light changes mid-mesh its answer depends on its cache's evictions;
     * here every read of the mesh sees the first.) 1/3 of a section's meshing was AO, mostly these lookups.
     */
    public static final boolean LIGHT = Boolean.getBoolean("lab.lightBox");
    private final int[] light = new int[N * N * N], lstamp = new int[N * N * N], sstamp = new int[N * N * N];
    private final float[] shade = new float[N * N * N];

    public int light(int i, net.minecraft.world.level.BlockAndTintGetter level, BlockState state, BlockPos pos) {
        if (lstamp[i] == gen) return light[i];
        int v = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, state, pos);
        light[i] = v;
        lstamp[i] = gen;
        return v;
    }

    public float shade(int i, net.minecraft.world.level.BlockAndTintGetter level, BlockState state, BlockPos pos) {
        if (sstamp[i] == gen) return shade[i];
        float v = state.getShadeBrightness(level, pos);
        shade[i] = v;
        sstamp[i] = gen;
        return v;
    }

    public BlockState kept(int i) {
        return stamp[i] == gen ? states[i] : null;
    }

    public void keep(int i, BlockState s) {
        states[i] = s;
        stamp[i] = gen;
    }
}
