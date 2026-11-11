package dev.lull.optsupport;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * exp/collide-kernel: Entity.collideBoundingBox's block part without its garbage. Vanilla builds, per move, an expanded
 * AABB, an ImmutableList of shapes (its builder, the BlockCollisions iterator, a MutableBlockPos, the entity box as a
 * VoxelShape, a collision context, every full block as a moved shape), and an AABB per axis step. Here the same shapes
 * in the same order - the entity colliders given, the world border, then BlockCollisions' cells in its cursor order
 * with its checks - go into this thread's arrays: a full block as its origin (three doubles), any other shape as the
 * object vanilla makes; collideWithShapes' axis order, Shapes.collide's early exit and every comparison are vanilla's,
 * the full block's collide being CubeAt's (VoxelShape.collideX specialised to the unit cube, bit for bit) and the box
 * steps AABB.move's arithmetic on doubles. The entity box as a VoxelShape and the collision context are made only when
 * a cell needs them (a non-cube shape, a block whose shape is not static).
 *
 * <p>Owner threads only (one kernel per side); off when another mod's mixin is anywhere in that path (its code would
 * be skipped). {@code -Dlab.opt.collideKernelCheck=true}: every result compared with vanilla's, bit for bit.
 */
public final class CollideKernel {
    /** -Dlab.opt.collideKernel=false: vanilla's collectColliders + collideWithShapes (A/B) */
    public static final boolean ON = !"false".equals(System.getProperty("lab.opt.collideKernel"));
    /** -Dlab.opt.collideKernelSupport=false: findSupportingBlock the vanilla way while the kernel collides (A/B) */
    public static final boolean SUPPORT = ON && !"false".equals(System.getProperty("lab.opt.collideKernelSupport"));
    public static final boolean CHECK = Boolean.getBoolean("lab.opt.collideKernelCheck");
    public static long calls, checked, mismatches;
    private static final CollideKernel SERVER = new CollideKernel(), CLIENT = new CollideKernel();

    private static Boolean usable;

    /** Not when another mod's mixin is in the code this replaces (its effect would be lost): vanilla then. */
    public static boolean usable() {
        Boolean u = usable;
        if (u == null) {
            u = !MixinTouch.touched("net/minecraft/world/entity/Entity", "collectColliders")
                    && !MixinTouch.touched("net/minecraft/world/entity/Entity", "collideWithShapes")
                    && !MixinTouch.touched("net/minecraft/world/level/CollisionGetter", "getBlockCollisions")
                    && !MixinTouch.touched("net/minecraft/world/level/Level", "getBlockCollisions")
                    && !MixinTouch.touched("net/minecraft/world/level/BlockCollisions", "computeNext")
                    && !MixinTouch.touched("net/minecraft/world/phys/shapes/Shapes", "collide")
                    && !MixinTouch.touched("net/minecraft/world/phys/shapes/VoxelShape", "collide")
                    && !MixinTouch.touched("net/minecraft/world/level/border/WorldBorder", "isInsideCloseToBorder");
            usable = u;
            System.out.println("[sleep/opt] collide kernel: " + (u ? "on" : "off (another mod's mixin in the collision path)"));
        }
        return u;
    }

    public static CollideKernel of(Level level) {
        if (ParticleWorkers.inWorker()) return WORKERS.get();
        return level.isClientSide() ? CLIENT : SERVER;
    }

    /** ParallelParticles' workers: a kernel each; non-static shapes come from the render thread, masks only when current */
    private static final ThreadLocal<CollideKernel> WORKERS = ThreadLocal.withInitial(() -> {
        CollideKernel k = new CollideKernel();
        k.worker = true;
        return k;
    });
    private boolean worker;

    // the collected shapes, in order: kind 0 a VoxelShape (shapes[i]), kind 1 a full block at (ox, oy, oz)[i]
    private byte[] kind = new byte[64];
    private VoxelShape[] shapes = new VoxelShape[64];
    private double[] ox = new double[64], oy = new double[64], oz = new double[64];
    private int n;
    /** a block's shape code asking for collisions itself: that nested query goes the vanilla way */
    private boolean busy;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    // the scan's chunk and mask caches (BlockCollisions' getChunk, BlockCollisionsFastMixin's masks)
    private BlockGetter chunk;
    private long chunkKey;
    private LevelChunk maskChunk;
    private int maskSy;
    private long[] mask;

    private void add(VoxelShape s) {
        grow();
        kind[n] = 0;
        shapes[n++] = s;
    }

    private void addCube(double x, double y, double z) {
        grow();
        kind[n] = 1;
        shapes[n] = null;
        ox[n] = x;
        oy[n] = y;
        oz[n++] = z;
    }

    private void grow() {
        if (n < kind.length) return;
        int c = kind.length * 2;
        kind = java.util.Arrays.copyOf(kind, c);
        shapes = java.util.Arrays.copyOf(shapes, c);
        ox = java.util.Arrays.copyOf(ox, c);
        oy = java.util.Arrays.copyOf(oy, c);
        oz = java.util.Arrays.copyOf(oz, c);
    }

    /**
     * Entity.collideBoundingBox's result for (entity, vec, collisionBox, level, potentialHits), bb being
     * collisionBox.expandTowards(vec): vanilla's collideWithShapes(vec, collisionBox, collectColliders(...)); null when
     * this kernel is busy (a nested query: the caller goes the vanilla way).
     */
    public Vec3 collide(Entity entity, Vec3 vec, AABB collisionBox, AABB bb, Level level, List<VoxelShape> potentialHits) {
        if (busy) return null;
        busy = true;
        calls++;
        n = 0;
        chunk = null;
        maskChunk = null;
        try {
            // collectColliders: the entity colliders, the world border near it, the block shapes
            for (int i = 0; i < potentialHits.size(); i++) add(potentialHits.get(i));
            WorldBorder wb = level.getWorldBorder();
            if (entity != null && wb.isInsideCloseToBorder(entity, bb)) add(wb.getCollisionShape());
            scan(entity, level, bb);
            Vec3 r = n == 0 ? vec : resolve(vec, collisionBox);
            if (CHECK) check(entity, vec, collisionBox, bb, level, potentialHits, r);
            return r;
        } finally {
            busy = false;
            for (int i = 0; i < n; i++) shapes[i] = null;
            chunk = null;
            maskChunk = null;
            mask = null;
        }
    }

    /**
     * ParticleShapeMemoMixin: while ParticleEngine.tick runs the client level does not change (packets are handled
     * outside it) and particles collide without an entity (the empty context), so a block whose shape is not static
     * gives the same shape at a position for every particle of that tick: asked once, kept moved to its cell (Shapes.block()
     * kept as itself: the cube path). Hundreds of working machines' particles asked Immersive Engineering's multiblock
     * parts for their shape - a block entity lookup each time - 84 % of the client thread.
     */
    private static boolean particleWindow;
    /**
     * The client's entity tick likewise (EntityShapeMemoMixin): entities ask with their own context, so only blocks
     * whose shape never reads it (ShapePurity.contextFree) are kept for them - Immersive Engineering's multiblock parts
     * under thousands of items a crusher dropped.
     */
    private static boolean entityWindow;
    /** per position: the state the shape was asked for and the shape moved to its cell (a block change there: asked again) */
    private record Cell(BlockState state, VoxelShape moved) {}
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Cell> PARTICLE_SHAPES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    public static long particleShapeHits, particleShapeMisses, entityShapeHits, entityShapeMisses, entityShapeBound;

    public static void particleTick(boolean starting) {
        particleWindow = starting;
        if (!PARTICLE_SHAPES.isEmpty()) PARTICLE_SHAPES.clear();
        if (!GETTER_SHAPES.isEmpty()) GETTER_SHAPES.clear();
    }

    public static void entityTick(boolean starting) {
        entityWindow = starting;
        if (!PARTICLE_SHAPES.isEmpty()) PARTICLE_SHAPES.clear();
        if (!GETTER_SHAPES.isEmpty()) GETTER_SHAPES.clear();
    }

    /** shapes asked through BlockCollisions (findSupportingBlock and the other vanilla iterators) with the chunk as getter */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Cell> GETTER_SHAPES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    public static long getterShapeHits, getterShapeMisses;

    /**
     * BlockCollisionsFastMixin, in place of BlockCollisions' getCollisionShape call: in the client's entity tick (its
     * owner thread) the position's shape kept as the kernel keeps it - asked once with a WatchedContext (or plainly when
     * ShapePurity proves the block context-free) and kept only if the context was not asked; otherwise vanilla's call.
     */
    public static VoxelShape windowShape(BlockState state, net.minecraft.world.level.BlockGetter getter, BlockPos pos, CollisionContext context,
                                         net.minecraft.world.level.CollisionGetter collisionGetter) {
        // (no client classes named: this is reached on dedicated servers too, where the window never opens)
        if (!entityWindow || !(collisionGetter instanceof net.minecraft.world.level.Level cl) || !cl.isClientSide() || !OwnerThread.owns(cl))
            return state.getCollisionShape(getter, pos, context);
        if (state instanceof BlockCollisionMemo.StaticShape st) {
            VoxelShape s = st.sleep$staticCollision();
            if (s != null) return s;
        }
        long key = pos.asLong();
        Cell cell = GETTER_SHAPES.get(key);
        if (cell != null && cell.state == state) {
            if (cell.moved == null) return state.getCollisionShape(getter, pos, context);
            getterShapeHits++;
            return cell.moved;
        }
        VoxelShape sh;
        boolean free = true;
        if (ShapePurity.contextFree(state.getBlock())) sh = state.getCollisionShape(getter, pos, context);
        else {
            WatchedContext w = new WatchedContext(context);
            sh = state.getCollisionShape(getter, pos, w);
            free = !w.asked;
        }
        GETTER_SHAPES.put(key, new Cell(state, free ? sh : null));   // (here unmoved: BlockCollisions moves it itself)
        getterShapeMisses++;
        return sh;
    }

    /** BlockCollisions over bb (onlySuffocatingBlocks false): its cursor, its checks, its shapes, in its order. */
    private void scan(Entity entity, Level level, AABB bb) {
        int x0 = net.minecraft.util.Mth.floor(bb.minX - 1.0E-7) - 1, x1 = net.minecraft.util.Mth.floor(bb.maxX + 1.0E-7) + 1;
        int y0 = net.minecraft.util.Mth.floor(bb.minY - 1.0E-7) - 1, y1 = net.minecraft.util.Mth.floor(bb.maxY + 1.0E-7) + 1;
        int z0 = net.minecraft.util.Mth.floor(bb.minZ - 1.0E-7) - 1, z1 = net.minecraft.util.Mth.floor(bb.maxZ + 1.0E-7) + 1;
        boolean masks = !level.isDebug() && CollisionMask.usable();
        CollisionContext context = null;
        VoxelShape entityShape = null;
        for (int z = z0; z <= z1; z++) {
            int tz = z == z0 || z == z1 ? 1 : 0;
            for (int y = y0; y <= y1; y++) {
                int ty = tz + (y == y0 || y == y1 ? 1 : 0);
                for (int x = x0; x <= x1; x++) {
                    int type = ty + (x == x0 || x == x1 ? 1 : 0);   // Cursor3D.getNextType
                    if (type == 3) continue;
                    BlockGetter g = chunkAt(level, x, z);
                    if (g == null) continue;
                    pos.set(x, y, z);
                    BlockState state;
                    if (masks && g instanceof LevelChunk lc && empty(lc, x, y, z)) state = Blocks.AIR.defaultBlockState();
                    else state = g.getBlockState(pos);
                    if ((type != 1 || state.hasLargeCollisionShape()) && (type != 2 || state.is(Blocks.MOVING_PISTON))) {
                        VoxelShape shape = state instanceof BlockCollisionMemo.StaticShape st ? st.sleep$staticCollision() : null;
                        Cell cell = null;
                        long key = 0;
                        if (shape == null && worker) {   // a particle on a pool worker: the render thread's shape for this cell
                            VoxelShape moved = ParticleWorkers.shape(state, level, x, y, z, entity);
                            if (moved == Shapes.block()) {
                                if (bb.intersects((double) x, (double) y, (double) z, (double) x + 1.0, (double) y + 1.0, (double) z + 1.0))
                                    hitCube(x, y, z);
                            } else if (!moved.isEmpty()) {
                                if (entityShape == null) entityShape = Shapes.create(bb);
                                if (Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) hit(moved, x, y, z);
                            }
                            continue;
                        }
                        // the windows are the client's (its render thread's): the server kernel never takes them
                        boolean window = this == CLIENT && (entity == null ? particleWindow : entityWindow);
                        if (shape == null && window) {
                            key = BlockPos.asLong(x, y, z);
                            cell = PARTICLE_SHAPES.get(key);
                            if (cell != null && (cell.state != state || cell.moved == null && entity == null)) cell = null;
                        }
                        if (shape == null && window && (cell == null || cell.moved != null)) {
                            // a particle, or an entity where the shape does not depend on who asks: the tick's shape at this cell, moved
                            VoxelShape moved;
                            if (cell == null) {
                                if (context == null) context = entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
                                VoxelShape sh;
                                boolean free = true;
                                if (entity == null || ShapePurity.contextFree(state.getBlock())) sh = state.getCollisionShape(level, pos, context);
                                else {   // watched: kept only if the context was not asked anything
                                    WatchedContext w = new WatchedContext(context);
                                    sh = state.getCollisionShape(level, pos, w);
                                    free = !w.asked;
                                }
                                moved = sh == Shapes.block() || sh.isEmpty() ? sh : sh.move((double) x, (double) y, (double) z);
                                PARTICLE_SHAPES.put(key, new Cell(state, free ? moved : null));
                                if (entity == null) ParticleWorkers.offer(state, x, y, z, moved);   // the workers of this tick may take it
                                if (entity == null) particleShapeMisses++;
                                else if (free) entityShapeMisses++;
                                else entityShapeBound++;
                            } else {
                                moved = cell.moved;
                                if (entity == null) particleShapeHits++;
                                else entityShapeHits++;
                            }
                            if (moved == Shapes.block()) {
                                if (bb.intersects((double) x, (double) y, (double) z, (double) x + 1.0, (double) y + 1.0, (double) z + 1.0))
                                    hitCube(x, y, z);
                            } else if (!moved.isEmpty()) {
                                if (entityShape == null) entityShape = Shapes.create(bb);
                                if (Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) hit(moved, x, y, z);
                            }
                            continue;
                        }
                        if (shape == null) {   // not static: the context is read
                            if (context == null) context = entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
                            shape = state.getCollisionShape(level, pos, context);
                        }
                        if (shape == Shapes.block()) {
                            if (bb.intersects((double) x, (double) y, (double) z, (double) x + 1.0, (double) y + 1.0, (double) z + 1.0))
                                hitCube(x, y, z);
                        } else if (!shape.isEmpty()) {
                            VoxelShape moved = shape.move((double) x, (double) y, (double) z);
                            if (!moved.isEmpty()) {
                                if (entityShape == null) entityShape = Shapes.create(bb);
                                if (Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) hit(moved, x, y, z);
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ findSupportingBlock on the same walk

    /** support mode: the scan's hits pick the supporting block instead of collecting shapes */
    private boolean support, found;
    private int bx, by, bz;
    private double bestD, epx, epy, epz;
    public static long supportCalls, supportChecked, supportMismatches;

    private void hit(VoxelShape moved, int x, int y, int z) {
        if (support) consider(x, y, z);
        else add(moved);
    }

    private void hitCube(int x, int y, int z) {
        if (support) consider(x, y, z);
        else addCube((double) x, (double) y, (double) z);
    }

    /** CollisionGetter.findSupportingBlock's choice: the least distToCenterSqr, a tie to the greater position (Vec3i.compareTo) */
    private void consider(int x, int y, int z) {
        double d0 = (double) x + 0.5 - epx, d1 = (double) y + 0.5 - epy, d2 = (double) z + 0.5 - epz;
        double d = d0 * d0 + d1 * d1 + d2 * d2;
        if (d < bestD || d == bestD && (!found || (by != y ? by < y : bz != z ? bz < z : bx < x))) {
            bx = x;
            by = y;
            bz = z;
            bestD = d;
            found = true;
        }
    }

    /**
     * CollisionGetter.findSupportingBlock(entity, box): the blocks BlockCollisions yields over the box (this scan: its
     * cursor, checks and shapes, the tick's shape memo) - the nearest to the entity's position. No iterator, no context,
     * no position objects for the blocks it passes.
     */
    public java.util.Optional<BlockPos> support(Entity entity, AABB box, Level level) {
        if (busy) return null;   // a nested query (a block's shape code asking for collisions): the caller goes the vanilla way
        busy = true;
        supportCalls++;
        support = true;
        found = false;
        bestD = Double.MAX_VALUE;
        Vec3 p = entity.position();
        epx = p.x;
        epy = p.y;
        epz = p.z;
        try {
            scan(entity, level, box);
        } finally {
            support = false;
            busy = false;
        }
        return found ? java.util.Optional.of(new BlockPos(bx, by, bz)) : java.util.Optional.empty();
    }

    private BlockGetter chunkAt(Level level, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        long key = ChunkPos.asLong(cx, cz);
        if (chunk != null && chunkKey == key) return chunk;
        BlockGetter g = level.getChunkForCollisions(cx, cz);
        chunk = g;
        chunkKey = key;
        return g;
    }

    private boolean empty(LevelChunk lc, int x, int y, int z) {
        int sy = y >> 4;
        if (lc != maskChunk || sy != maskSy) {
            maskChunk = lc;
            maskSy = sy;
            int idx = lc.getSectionIndexFromSectionY(sy);
            LevelChunkSection s = idx >= 0 && idx < lc.getSections().length ? lc.getSection(idx) : null;
            mask = s instanceof CollisionMask.Holder h ? (worker ? h.sleep$maskIfCurrent() : h.sleep$mask()) : null;
        }
        long[] m = mask;
        if (m == null) return false;
        int b = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        return (m[b >>> 6] & 1L << b) == 0;
    }

    // ------------------------------------------------------------------ Entity.collideWithShapes on the arrays

    private double minX, minY, minZ, maxX, maxY, maxZ;

    private Vec3 resolve(Vec3 delta, AABB box) {
        minX = box.minX;
        minY = box.minY;
        minZ = box.minZ;
        maxX = box.maxX;
        maxY = box.maxY;
        maxZ = box.maxZ;
        double d0 = delta.x, d1 = delta.y, d2 = delta.z;
        if (d1 != 0.0) {
            d1 = collide(1, d1);
            if (d1 != 0.0) move(0.0, d1, 0.0);
        }
        boolean flag = Math.abs(d0) < Math.abs(d2);
        if (flag && d2 != 0.0) {
            d2 = collide(2, d2);
            if (d2 != 0.0) move(0.0, 0.0, d2);
        }
        if (d0 != 0.0) {
            d0 = collide(0, d0);
            if (!flag && d0 != 0.0) move(d0, 0.0, 0.0);
        }
        if (!flag && d2 != 0.0) d2 = collide(2, d2);
        return new Vec3(d0, d1, d2);
    }

    /** AABB.move: the AABB constructor over the moved corners */
    private void move(double x, double y, double z) {
        double ax = minX + x, ay = minY + y, az = minZ + z, bx = maxX + x, by = maxY + y, bz = maxZ + z;
        minX = Math.min(ax, bx);
        minY = Math.min(ay, by);
        minZ = Math.min(az, bz);
        maxX = Math.max(ax, bx);
        maxY = Math.max(ay, by);
        maxZ = Math.max(az, bz);
    }

    /** Shapes.collide over the collected shapes along axis (0 x, 1 y, 2 z) */
    private double collide(int axis, double d) {
        AABB box = null;
        for (int i = 0; i < n; i++) {
            if (Math.abs(d) < 1.0E-7) return 0.0;
            if (kind[i] == 1) d = cube(axis, ox[i], oy[i], oz[i], d);
            else {
                if (box == null) box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
                d = shapes[i].collide(axis == 0 ? Direction.Axis.X : axis == 1 ? Direction.Axis.Y : Direction.Axis.Z, box, d);
            }
        }
        return d;
    }

    private double min(int axis) {
        return axis == 0 ? minX : axis == 1 ? minY : minZ;
    }

    private double max(int axis) {
        return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
    }

    /** CubeAt.findIndex: -1 below the cube, 0 inside, 1 at or above its far face (offset o on that axis) */
    private static int findIndex(double o, double position) {
        if (position < 0.0 / 1.0 + o) return -1;
        return position < 1.0 / 1.0 + o ? 0 : 1;
    }

    /**
     * CubeAt.collideCube (VoxelShape.collideX for the full block at x, y, z): the movement axis a, then the other two in
     * AxisCycle's order (x: y, z; y: z, x; z: x, y).
     */
    private double cube(int a, double x, double y, double z, double d) {
        if (Math.abs(d) < 1.0E-7) return 0.0;
        int a1 = a == 0 ? 1 : a == 1 ? 2 : 0, a2 = a == 0 ? 2 : a == 1 ? 0 : 1;
        double oa = a == 0 ? x : a == 1 ? y : z, o1 = a1 == 0 ? x : a1 == 1 ? y : z, o2 = a2 == 0 ? x : a2 == 1 ? y : z;
        double d0 = max(a);
        double d1 = min(a);
        int i = findIndex(oa, d1 + 1.0E-7);
        int j = findIndex(oa, d0 - 1.0E-7);
        int k = Math.max(0, findIndex(o1, min(a1) + 1.0E-7));
        int l = Math.min(1, findIndex(o1, max(a1) - 1.0E-7) + 1);
        int i1 = Math.max(0, findIndex(o2, min(a2) + 1.0E-7));
        int j1 = Math.min(1, findIndex(o2, max(a2) - 1.0E-7) + 1);
        boolean cross = k < l && i1 < j1;
        if (d > 0.0) {
            if (j + 1 < 1 && cross) {
                double d2 = ((double) 0 / 1.0 + oa) - d0;
                if (d2 >= -1.0E-7) d = Math.min(d, d2);
                return d;
            }
        } else if (d < 0.0) {
            if (i - 1 >= 0 && cross) {
                double d3 = ((double) 1 / 1.0 + oa) - d1;
                if (d3 <= 1.0E-7) d = Math.max(d, d3);
                return d;
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ -Dlab.opt.collideKernelCheck=true

    private static void check(Entity entity, Vec3 vec, AABB collisionBox, AABB bb, Level level, List<VoxelShape> potentialHits, Vec3 r) {
        checked++;
        Vec3 v = vanilla(entity, vec, collisionBox, bb, level, potentialHits);
        boolean ok = (v == vec) == (r == vec) && Double.doubleToRawLongBits(v.x) == Double.doubleToRawLongBits(r.x)
                && Double.doubleToRawLongBits(v.y) == Double.doubleToRawLongBits(r.y) && Double.doubleToRawLongBits(v.z) == Double.doubleToRawLongBits(r.z);
        if (!ok && mismatches++ < 5) System.out.println("[sleep/opt] collide kernel MISMATCH for " + entity + " vec " + vec + ": kernel " + r + " vanilla " + v);
    }

    /** the vanilla steps (CollideKernelAccess: Entity's private collectColliders / collideWithShapes) */
    public interface Vanilla {
        Vec3 sleep$vanillaCollide(Entity entity, Vec3 vec, AABB collisionBox, AABB bb, Level level, List<VoxelShape> potentialHits);
    }

    public static Vanilla vanilla;

    private static Vec3 vanilla(Entity entity, Vec3 vec, AABB collisionBox, AABB bb, Level level, List<VoxelShape> potentialHits) {
        return vanilla.sleep$vanillaCollide(entity, vec, collisionBox, bb, level, potentialHits);
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "collide kernel: calls %d, checked %d, MISMATCHES %d, particle shapes kept %d (asked %d), entity shapes kept %d (asked %d, bound to the context %d; blocks proven context-free %d, not %d), iterator shapes kept %d (asked %d)", calls, checked,
                mismatches, particleShapeHits, particleShapeMisses, entityShapeHits, entityShapeMisses, entityShapeBound, ShapePurity.contextFree, ShapePurity.contextBound,
                getterShapeHits, getterShapeMisses);
    }
}
