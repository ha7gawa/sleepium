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

    /** A chunk section's mask (SectionModCountMixin), current for its contents. */
    public interface Holder {
        long[] sleep$mask();

        /** the mask only if it is built for the section as it is (no build: other threads may read it meanwhile), else null */
        long[] sleep$maskIfCurrent();
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
