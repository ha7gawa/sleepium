package dev.lull.optsupport;

import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Per-entity memo of the block part of noCollision / noBlockCollision (BlockCollisionMemoMixin): the query box, and the
 * chunk sections covering the scanned block range with their modification counters when the answer was computed.
 * Valid only while every state in that range had a provably static collision shape (ShapePurity).
 */
public final class BlockCollisionMemo {
    public static final int MAX_SECTIONS = 8;
    /** -Dlab.opt.collisionMemoCheck=true: recompute vanilla on every hit and count differences */
    public static final boolean CHECK = Boolean.getBoolean("lab.opt.collisionMemoCheck");
    /** -Dlab.memo.basicOnly=true: only the noCollision slot (A/B of the collide / supporting-block slots) */
    public static final boolean MOVE_SLOTS = !Boolean.getBoolean("lab.memo.basicOnly");

    public double minX, minY, minZ, maxX, maxY, maxZ;
    public final Object[] sections = new Object[MAX_SECTIONS];
    public final int[] mods = new int[MAX_SECTIONS];
    public int n;
    public boolean valid, free;
    /** extra key (movement or position) and the stored answer of the collide / supporting-block slots */
    public double k0, k1, k2, r0, r1, r2;
    public Object result;
    public boolean same;

    public static long hits, misses, unmemoizable, collideHits, collideMisses, supportHits, supportMisses;

    public boolean sameKey(double a, double b, double c) {
        return a == k0 && b == k1 && c == k2;
    }

    public void setKey(double a, double b, double c) {
        k0 = a;
        k1 = b;
        k2 = c;
    }

    /**
     * Record the sections covering the block range BlockCollisions scans for {@code bb} (its constructor's formula);
     * false if a chunk is missing or any state in the range lacks a provably static collision shape.
     */
    public static boolean record(CollisionGetter level, BlockCollisionMemo m, AABB bb) {
        int x0 = Mth.floor(bb.minX - 1.0E-7) - 1, x1 = Mth.floor(bb.maxX + 1.0E-7) + 1;
        int y0 = Mth.floor(bb.minY - 1.0E-7) - 1, y1 = Mth.floor(bb.maxY + 1.0E-7) + 1;
        int z0 = Mth.floor(bb.minZ - 1.0E-7) - 1, z1 = Mth.floor(bb.maxZ + 1.0E-7) + 1;
        int k = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                BlockGetter g = level.getChunkForCollisions(cx, cz);
                if (!(g instanceof LevelChunk chunk) || chunk.getLevel().isDebug()) return false;
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int idx = chunk.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSections().length) continue;   // outside the world: always air
                    if (k == MAX_SECTIONS) return false;
                    LevelChunkSection s = chunk.getSection(idx);
                    if (!s.hasOnlyAir()) {
                        int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                        int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                        int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                        for (int x = ax; x <= bx; x++)
                            for (int y = ay; y <= by; y++)
                                for (int z = az; z <= bz; z++)
                                    if (!(s.getBlockState(x & 15, y & 15, z & 15) instanceof StaticShape st) || !st.sleep$staticShape()) return false;
                    }
                    if (!(s instanceof Counted counted)) return false;   // (its mixin gave way to another mod)
                    m.sections[k] = s;
                    m.mods[k] = counted.sleep$mod();
                    k++;
                }
            }
        }
        m.n = k;
        return true;
    }

    /** -Dlab.memo.eager=true: record on every miss (before: moving entities paid the scan every tick for no hit) */
    public static final boolean EAGER = Boolean.getBoolean("lab.memo.eager");
    public static long deferred;

    /**
     * Record only for a box already seen on the previous miss: an entity moving every tick (falling blocks, bouncing
     * mobs) gets a new box every time and never pays the recording scan; a resting one is memoized one query later.
     */
    public static boolean worthRecording(BlockCollisionMemo m, AABB bb) {
        if (EAGER || m.sameBox(bb)) return true;
        m.setBox(bb);
        deferred++;
        return false;
    }

    /** The same sections (identity) with the same modification counters, chunks still loaded. */
    public static boolean unchanged(CollisionGetter level, BlockCollisionMemo m, AABB bb) {
        int x0 = Mth.floor(bb.minX - 1.0E-7) - 1, x1 = Mth.floor(bb.maxX + 1.0E-7) + 1;
        int y0 = Mth.floor(bb.minY - 1.0E-7) - 1, y1 = Mth.floor(bb.maxY + 1.0E-7) + 1;
        int z0 = Mth.floor(bb.minZ - 1.0E-7) - 1, z1 = Mth.floor(bb.maxZ + 1.0E-7) + 1;
        int k = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                BlockGetter g = level.getChunkForCollisions(cx, cz);
                if (!(g instanceof LevelChunk chunk)) return false;
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int idx = chunk.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSections().length) continue;
                    if (k == m.n) return false;
                    LevelChunkSection s = chunk.getSection(idx);
                    if (m.sections[k] != s || !(s instanceof Counted counted) || m.mods[k] != counted.sleep$mod()) return false;
                    k++;
                }
            }
        }
        return k == m.n;
    }

    /**
     * Memo use is allowed on the thread that owns the level's logic (OwnerThread): the server thread, or the client
     * main thread for client levels (client entities and sections are separate objects from the server's; one writer
     * and reader per entity). Chunk-compile workers never call it.
     */
    public static boolean usable(net.minecraft.world.level.Level level) {
        return OwnerThread.owns(level);
    }

    public boolean sameBox(AABB b) {
        return b.minX == minX && b.minY == minY && b.minZ == minZ && b.maxX == maxX && b.maxY == maxY && b.maxZ == maxZ;
    }

    public void setBox(AABB b) {
        minX = b.minX;
        minY = b.minY;
        minZ = b.minZ;
        maxX = b.maxX;
        maxY = b.maxY;
        maxZ = b.maxZ;
    }

    /** Implemented on Entity: one slot per memoized query. */
    public interface Holder {
        BlockCollisionMemo sleep$collisionMemo();

        void sleep$setCollisionMemo(BlockCollisionMemo m);

        BlockCollisionMemo sleep$collideMemo();

        void sleep$setCollideMemo(BlockCollisionMemo m);

        BlockCollisionMemo sleep$supportMemo();

        void sleep$setSupportMemo(BlockCollisionMemo m);
    }

    /** Implemented on LevelChunkSection: bumped on every block-state write. */
    public interface Counted {
        int sleep$mod();

        /** a write that went around setBlockState (FillWriter's packed writes into a fresh section) */
        void sleep$touch();
    }

    /** Implemented on BlockState (ShapeCacheMixin): collision shape proven to depend on the state alone. */
    public interface StaticShape {
        boolean sleep$staticShape();

        /** the state's collision shape when it is static (the very object every call returns), else null */
        net.minecraft.world.phys.shapes.VoxelShape sleep$staticCollision();
    }
}
