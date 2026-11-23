package dev.lull.optsupport;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * exp/collision-mask: which blocks of a chunk section can collide at all - a 4096-bit mask per section, built from the
 * states' proven collision shapes (ShapePurity via ShapeCacheMixin: a state whose shape is static and empty never
 * collides, in any context, at any position) and rebuilt only when the section changed (SectionModCountMixin's counter:
 * every write of its states). BlockCollisionsFastMixin walks the same cursor as vanilla and skips the cells whose bit
 * is clear without reading the state - air, grass, flowers, torches... - so a moving entity's block scan costs a bit
 * test per empty cell; every other cell goes the vanilla way, in vanilla's order (Shapes.collide's early exit makes the
 * order matter). {@code -Dlab.opt.collisionMaskCheck=true}: every skipped cell is asked the vanilla way too.
 */
public final class CollisionMask {
    public static final boolean CHECK = Boolean.getBoolean("lab.opt.collisionMaskCheck");
    public static long skipped, built, checked, mismatches;

    private CollisionMask() {}

    public static long clearHits, clearMisses;

    /**
     * every block cell of box (BlockPos.betweenClosedStream's cells: floor of each bound, inclusive) lies in a loaded
     * chunk section whose current mask has its bit clear - a state that never collides. The owner thread may build a
     * mask; any other thread takes one only if current (InWallMaskMixin).
     */
    public static boolean allClear(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
        if (!usable()) return false;
        int x0 = net.minecraft.util.Mth.floor(box.minX), y0 = net.minecraft.util.Mth.floor(box.minY), z0 = net.minecraft.util.Mth.floor(box.minZ);
        int x1 = net.minecraft.util.Mth.floor(box.maxX), y1 = net.minecraft.util.Mth.floor(box.maxY), z1 = net.minecraft.util.Mth.floor(box.maxZ);
        boolean own = OwnerThread.owns(level);
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!(level.getChunkSource().getChunkNow(cx, cz) instanceof net.minecraft.world.level.chunk.LevelChunk c)) {
                    clearMisses++;
                    return false;
                }
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int idx = c.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= c.getSections().length) {   // (outside the build height: vanilla reads air there - no collision)
                        continue;
                    }
                    LevelChunkSection s = c.getSection(idx);
                    long[] m = s instanceof Holder h ? (own ? h.sleep$mask() : h.sleep$maskIfCurrent()) : null;
                    if (m == null) {
                        clearMisses++;
                        return false;
                    }
                    int ya = Math.max(y0, sy << 4), yb = Math.min(y1, (sy << 4) + 15);
                    int xa = Math.max(x0, cx << 4), xb = Math.min(x1, (cx << 4) + 15), za = Math.max(z0, cz << 4), zb = Math.min(z1, (cz << 4) + 15);
                    for (int y = ya; y <= yb; y++)
                        for (int z = za; z <= zb; z++)
                            for (int x = xa; x <= xb; x++) {
                                int b = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                                if ((m[b >>> 6] & 1L << b) != 0) {
                                    clearMisses++;
                                    return false;
                                }
                            }
                }
            }
        clearHits++;
        return true;
    }

    /** A chunk section's mask (SectionModCountMixin), current for its contents. */
    public interface Holder {
        long[] sleep$mask();

        /** the mask only if it is built for the section as it is (no build: other threads may read it meanwhile), else null */
        long[] sleep$maskIfCurrent();

        /** no block state in the section's palette has a fluid (FluidScanDryMixin), kept per modification count */
        boolean sleep$fluidFree();
    }

    private static Boolean usable;

    /**
     * Not when another mod's mixin is in the state reads the mask stands for (a block that answers differently from
     * the section's palette): then every cell goes the vanilla way.
     */
    public static boolean usable() {
        Boolean u = usable;
        if (u == null) {
            u = !MixinTouch.touched("net/minecraft/world/level/chunk/LevelChunk", "getBlockState")
                    && !MixinTouch.touched("net/minecraft/world/level/chunk/LevelChunkSection", "getBlockState");
            usable = u;
            System.out.println("[sleep/opt] collision mask: " + (u ? "on" : "off (another mod's mixin in the chunk's block state reads)"));
        }
        return u;
    }

    /** whether this state can ever have a non-empty collision shape (unknown: yes) */
    public static boolean mayCollide(BlockState s) {
        if (!(s instanceof BlockCollisionMemo.StaticShape st)) return true;
        VoxelShape v = st.sleep$staticCollision();
        return v == null || !v.isEmpty();
    }

    /** The section's mask into m: bit (y << 8 | z << 4 | x) set where the state may collide. */
    public static void build(LevelChunkSection s, long[] m) {
        java.util.Arrays.fill(m, 0L);
        built++;
        if (s.hasOnlyAir() || !s.maybeHas(CollisionMask::mayCollide)) return;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if (mayCollide(s.getBlockState(x, y, z))) {
                        int i = y << 8 | z << 4 | x;
                        m[i >>> 6] |= 1L << i;
                    }
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "collision mask: cells skipped %d, masks built %d, checked %d, MISMATCHES %d", skipped, built, checked, mismatches);
    }
}
