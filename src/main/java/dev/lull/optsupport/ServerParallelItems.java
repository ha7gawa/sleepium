package dev.lull.optsupport;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * lab (notes/89, -Dlab.opt.ServerParallelItemsMixin=true, off by default): dropped items of the server's entity walk
 * ticked on the server pool, each exactly as vanilla ticks it, in an order that keeps every result vanilla's.
 *
 * <p>The walk ticks as vanilla does, except at a run - consecutive items of the walk that may tick apart. Before a run
 * each item's possible reach this tick is bounded from its state: its box swept by its motion (gravity, water and the
 * push out of a block need less than {@link #SLACK}) = the hull; an item whose merge check can come this tick
 * ({@code tickCount % 2}, the period when it changes block; 40 otherwise) also reads the items in hull + 0.5
 * horizontally and may change or remove them. Two run items depend on each other when one's merge box meets the
 * other's hull; the dependent one ticks after (a graph of levels, each level on the pool in parallel). An item may tick
 * apart only when nothing else can see or change it this tick: an exact ItemEntity of a plain item (no
 * onEntityItemUpdate of its own), no expiry this tick, not burning, not in lava or a portal, its hull inside one
 * entity section (no storage move), only vanilla blocks without entity callbacks (entityInside, stepOn, fallOn,
 * updateEntityAfterFallOn) and no lava / non-vanilla fluid around it, no water it could enter now (the splash sound),
 * no collidable entity (boats, shulkers) near, no merge contact with an item outside the run, and Sleepium's entity
 * sleep ticks it plainly this tick (no observation / replay - EntitySleep.plainNext). The EntityTickEvent listeners
 * must be inert for items (ParallelEntities' analysis).
 *
 * <p>During a run: storage removals (merges discard) and the sleep engine's wake events are deferred and done after
 * the run in its order; chunk lookups are served from the chunks resolved before it. A world write, spawn, effect or
 * a draw from the level's random on a run tick is a trap: counted, the feature switched off (its tick may have left
 * vanilla's order). {@code -Dlab.spi.alternate=true}: every other walk serial, both walk times reported.
 */
public final class ServerParallelItems {
    private ServerParallelItems() {}

    static final int MIN = Integer.getInteger("lab.spi.min", 32);
    static final boolean ALTERNATE = Boolean.getBoolean("lab.spi.alternate");
    static final double SLACK = 0.25;

    public static volatile boolean enabled = true;
    private static Entity[] all = new Entity[4096];
    private static int n;
    private static volatile boolean running;
    private static final Long2ObjectOpenHashMap<LevelChunk> CHUNKS = new Long2ObjectOpenHashMap<>();
    private static final ThreadLocal<int[]> CUR = ThreadLocal.withInitial(() -> new int[] {-1});
    private static EntityInLevelCallback[] removedCb = new EntityInLevelCallback[4096];
    private static Entity.RemovalReason[] removedWhy = new Entity.RemovalReason[4096];
    @SuppressWarnings("unchecked")
    private static List<Runnable>[] deferred = new List[4096];
    private static final java.util.IdentityHashMap<Entity, Integer> INDEX = new java.util.IdentityHashMap<>();
    // a run's items: hulls, merge boxes, levels
    private static AABB[] hull = new AABB[4096], reach = new AABB[4096];
    private static boolean[] merges = new boolean[4096];
    private static int[] level = new int[4096];
    static long walks, runs, inRuns, levelsSum, deferredMoves, deferredRemoves, deferredWakes, foreignRemoves, chunkMisses, traps,
            nsSerial, nsParallel, walksSerial, walksParallel;
    static final Map<String, Long> NOT = new ConcurrentHashMap<>();

    public static void forEach(net.minecraft.world.level.entity.EntityTickList list, Consumer<Entity> consumer, ServerLevel lvl) {
        if (SpecTicks.ON) {
            SpecTicks.forEach(list, consumer, lvl);
            return;
        }
        if (!enabled || lvl.getProfiler() != net.minecraft.util.profiling.InactiveProfiler.INSTANCE || ParallelEntities.tickListenersActing()) {
            list.forEach(consumer);
            return;
        }
        n = 0;
        list.forEach(e -> {
            if (n == all.length) all = java.util.Arrays.copyOf(all, n * 2);
            all[n++] = e;
        });
        walks++;
        boolean serial = ALTERNATE && (walks & 1) == 0;
        long t0 = System.nanoTime();
        try {
            int i = 0;
            while (i < n) {
                if (serial || !enabled || !(all[i] instanceof ItemEntity)) {
                    consumer.accept(all[i++]);
                    continue;
                }
                int j = i;
                while (j < n && all[j] instanceof ItemEntity) j++;
                i = window(i, j, consumer, lvl);
            }
        } finally {
            long dt = System.nanoTime() - t0;
            if (serial) { nsSerial += dt; walksSerial++; } else { nsParallel += dt; walksParallel++; }
            java.util.Arrays.fill(all, 0, n, null);
            if (walks % 400 == 0) report();
        }
    }

    /**
     * SpecTicks' walk (lab.spec): its array is this walk's - the item windows below read the walk through it (the
     * collidable entities once per walk, the indexes, the hulls); called once a walk before its first item window.
     */
    static void walkFrom(Entity[] arr, int count) {
        all = arr;
        n = count;
        walks++;
    }

    /** SpecTicks' walk: items [from, to) through this class's windows; returns where the walk goes on */
    static int itemsWindow(int from, int to, Consumer<Entity> consumer, ServerLevel lvl) {
        return window(from, to, consumer, lvl);
    }

    /** items [from, to) of the walk: the longest prefix that may tick apart runs in parallel; returns where the walk goes on */
    private static int window(int from, int to, Consumer<Entity> consumer, ServerLevel lvl) {
        grow(to);
        int end = from;
        while (end < to && eligible(all[end], end, lvl)) end++;
        // merge contact with an item outside [from, end): that item's tick order is not the run's - the prefix ends there
        INDEX.clear();
        for (int k = from; k < end; k++) INDEX.put(all[k], k);
        while (end - from >= MIN) {
            int cut = contact(from, end, lvl);
            if (cut < 0) break;
            end = cut;
        }
        if (end - from < MIN) {
            int stop = Math.max(end, from + 1);   // the short prefix and the one that ended it tick in their slots
            for (int k = from; k < stop && k < to; k++) consumer.accept(all[k]);
            return Math.min(stop, to);
        }
        run(from, end, consumer, lvl);
        return end;
    }

    private static void grow(int need) {
        if (hull.length >= need) return;
        int len = Math.max(need, hull.length * 2);
        hull = java.util.Arrays.copyOf(hull, len);
        reach = java.util.Arrays.copyOf(reach, len);
        merges = java.util.Arrays.copyOf(merges, len);
        level = java.util.Arrays.copyOf(level, len);
        removedCb = java.util.Arrays.copyOf(removedCb, len);
        removedWhy = java.util.Arrays.copyOf(removedWhy, len);
        deferred = java.util.Arrays.copyOf(deferred, len);
    }

    // ------------------------------------------------------------------ may an item tick apart

    private static final Map<Class<?>, Boolean> PLAIN_ITEM = new ConcurrentHashMap<>(), PLAIN_BLOCK = new ConcurrentHashMap<>();

    private static boolean no(String why) {
        NOT.merge(why, 1L, Long::sum);
        return false;
    }

    static boolean eligible(Entity e, int k, ServerLevel lvl) {
        if (e.getClass() != ItemEntity.class) return no("subclass");
        ItemEntity ie = (ItemEntity) e;
        if (e.isRemoved() || e.isPassenger() || e.isVehicle()) return no("removed / riding");
        if (ie.getItem().isEmpty()) return no("empty");
        if (!PLAIN_ITEM.computeIfAbsent(ie.getItem().getItem().getClass(), ServerParallelItems::plainItem)) return no("item update hook");
        int age = ie.getAge();
        if (age != -32768 && age + 1 >= ie.lifespan) return no("expires");
        if (e.getRemainingFireTicks() > 0 || e.isOnFire() || e.isInLava() || ((dev.lull.opt.ServerParallelItemsPortalAccessor) e).sleep$insidePortal()) return no("fire / lava / portal");
        if (e.getY() < lvl.getMinBuildHeight() - 32) return no("below the world");
        if (!dev.lull.optsupport.sleep.EntitySleep.plainNext(e)) return no("entity sleep observes");
        // the hull: the box swept by the motion, with slack for gravity, water, the push out of a block
        AABB bb = e.getBoundingBox();
        Vec3 v = e.getDeltaMovement();
        AABB h = bb.minmax(bb.move(v)).inflate(SLACK);
        if (SectionPos.blockToSectionCoord(Mth.floor(h.minX)) != SectionPos.blockToSectionCoord(Mth.floor(h.maxX))
                || SectionPos.blockToSectionCoord(Mth.floor(h.minY)) != SectionPos.blockToSectionCoord(Mth.floor(h.maxY))
                || SectionPos.blockToSectionCoord(Mth.floor(h.minZ)) != SectionPos.blockToSectionCoord(Mth.floor(h.maxZ)))
            return no("may change section");
        if (!blocksPlain(lvl, e, h)) return false;
        if (collidableNear(lvl, h.inflate(1.0))) return no("collidable entity near");
        // the merge check can come this tick: every 2 ticks when the item changes block, every 40 otherwise
        int tc = e.tickCount + 1;
        boolean changesBlock = Mth.floor(h.minX + SLACK) != Mth.floor(h.maxX - SLACK) || Mth.floor(h.minY + SLACK) != Mth.floor(h.maxY - SLACK)
                || Mth.floor(h.minZ + SLACK) != Mth.floor(h.maxZ - SLACK);
        boolean m = changesBlock ? tc % 2 == 0 : tc % 40 == 0;
        hull[k] = h;
        merges[k] = m;
        reach[k] = m ? h.inflate(0.5 + 1e-7, 0, 0.5 + 1e-7) : h;
        return true;
    }

    /** an Item class without an onEntityItemUpdate of its own (NeoForge's IItemExtension default: false) */
    private static boolean plainItem(Class<?> c) {
        try {
            return c.getMethod("onEntityItemUpdate", net.minecraft.world.item.ItemStack.class, ItemEntity.class).getDeclaringClass().isInterface();
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /** a vanilla block whose class has none of the entity callbacks of a moving entity */
    private static boolean plainBlock(Class<?> c) {
        if (!c.getName().startsWith("net.minecraft.")) return false;
        for (String m : new String[] {"entityInside", "stepOn", "fallOn", "updateEntityAfterFallOn"})
            for (Class<?> x = c; x != null && x != Block.class && x != net.minecraft.world.level.block.state.BlockBehaviour.class; x = x.getSuperclass())
                for (java.lang.reflect.Method dm : x.getDeclaredMethods()) if (dm.getName().equals(m)) return false;
        return true;
    }

    private static boolean blocksPlain(ServerLevel lvl, Entity e, AABB h) {
        int x0 = Mth.floor(h.minX) - 1, y0 = Mth.floor(h.minY) - 1, z0 = Mth.floor(h.minZ) - 1;
        int x1 = Mth.floor(h.maxX) + 1, y1 = Mth.floor(h.maxY) + 1, z1 = Mth.floor(h.maxZ) + 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean inWater = e.isInWater();
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    BlockState s = lvl.getBlockState(p.set(x, y, z));
                    if (!PLAIN_BLOCK.computeIfAbsent(s.getBlock().getClass(), ServerParallelItems::plainBlock)) return no("block " + s.getBlock().getClass().getSimpleName());
                    FluidState f = s.getFluidState();
                    if (!f.isEmpty()) {
                        if (!f.is(Fluids.WATER) && !f.is(Fluids.FLOWING_WATER)) return no("fluid not water");
                        if (!inWater) return no("water it may enter");
                    }
                }
        return true;
    }

    private static final List<AABB> COLLIDABLE = new ArrayList<>();
    private static long collidableWalk = -1;

    private static boolean collidableNear(ServerLevel lvl, AABB box) {
        if (collidableWalk != walks) {   // the walk's collidable entities, once per walk (their boxes as the run starts:
            collidableWalk = walks;      // earlier ones have ticked, later ones have not - both as vanilla's item sees them)
            COLLIDABLE.clear();
            for (int i = 0; i < n; i++) if (all[i].canBeCollidedWith()) COLLIDABLE.add(all[i].getBoundingBox());
        }
        for (AABB c : COLLIDABLE) if (c.intersects(box)) return true;
        return false;
    }

    /**
     * a run item [from, end) whose merge box meets an item of the level outside the run: the first such index (the run
     * ends before it), or -1. Grid of the run's hulls / reaches by block column.
     */
    private static int contact(int from, int end, ServerLevel lvl) {
        for (int k = from; k < end; k++) {
            if (!merges[k]) continue;
            for (Entity o : lvl.getEntities((Entity) null, reach[k], x -> x instanceof ItemEntity)) {
                Integer idx = INDEX.get(o);
                if (idx == null || idx >= end) {
                    NOT.merge("merge contact outside the run", 1L, Long::sum);
                    return k;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ the run: levels of the dependency graph

    private static final Long2ObjectOpenHashMap<IntArrayList> GRID = new Long2ObjectOpenHashMap<>();

    private static int levels(int from, int end) {
        GRID.clear();
        int max = 0;
        for (int k = from; k < end; k++) {
            int lv = 0;
            AABB r = reach[k];
            int x0 = Mth.floor(r.minX), x1 = Mth.floor(r.maxX), z0 = Mth.floor(r.minZ), z1 = Mth.floor(r.maxZ);
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    IntArrayList l = GRID.get(ChunkPos.asLong(x, z));
                    if (l == null) continue;
                    for (int q = 0; q < l.size(); q++) {
                        int j = l.getInt(q);
                        if (level[j] + 1 <= lv) continue;
                        if (merges[k] && reach[k].intersects(hull[j]) || merges[j] && reach[j].intersects(hull[k])) lv = level[j] + 1;
                    }
                }
            level[k] = lv;
            if (lv > max) max = lv;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) GRID.computeIfAbsent(ChunkPos.asLong(x, z), q -> new IntArrayList()).add(k);
        }
        return max + 1;
    }

    private static void run(int from, int end, Consumer<Entity> consumer, ServerLevel lvl) {
        runs++;
        inRuns += end - from;
        CHUNKS.clear();
        INDEX.clear();
        for (int k = from; k < end; k++) INDEX.put(all[k], k);
        for (int k = from; k < end; k++) {
            AABB r = reach[k].inflate(1.0);
            for (int cx = SectionPos.blockToSectionCoord(Mth.floor(r.minX)); cx <= SectionPos.blockToSectionCoord(Mth.floor(r.maxX)); cx++)
                for (int cz = SectionPos.blockToSectionCoord(Mth.floor(r.minZ)); cz <= SectionPos.blockToSectionCoord(Mth.floor(r.maxZ)); cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    if (!CHUNKS.containsKey(key)) CHUNKS.put(key, lvl.getChunkSource().getChunkNow(cx, cz));
                }
        }
        int nl = levels(from, end);
        levelsSum += nl;
        IntArrayList[] byLevel = new IntArrayList[nl];
        for (int k = from; k < end; k++) (byLevel[level[k]] == null ? byLevel[level[k]] = new IntArrayList() : byLevel[level[k]]).add(k);
        running = true;
        try {
            var pool = ServerReactor.pool();
            for (IntArrayList l : byLevel) {
                if (l == null) continue;
                pool.forRange(l.size(), 8, pool.workerCount(), q -> {
                    int k = l.getInt(q);
                    int[] cur = CUR.get();
                    cur[0] = k;
                    try {
                        consumer.accept(all[k]);
                    } finally {
                        cur[0] = -1;
                    }
                });
            }
        } finally {
            running = false;
        }
        // deferred work in the run's order: each item's wake events, then its removal from the storage
        for (int k = from; k < end; k++) {
            List<Runnable> d = deferred[k];
            if (d != null) {
                deferred[k] = null;
                for (Runnable r : d) {
                    deferredWakes++;
                    r.run();
                }
            }
            EntityInLevelCallback cb = removedCb[k];
            if (cb != null) {
                removedCb[k] = null;
                deferredRemoves++;
                cb.onRemove(removedWhy[k]);
                removedWhy[k] = null;
            }
            hull[k] = reach[k] = null;
        }
    }

    // ------------------------------------------------------------------ hooks

    public static boolean inRun() {
        return running;
    }

    public static boolean running() {
        return running;
    }

    /** a run tick's event for after the run (the sleep engine's wakes), in the order of the item that raised it */
    public static boolean defer(Runnable r) {
        if (!running) return false;
        int k = CUR.get()[0];
        if (k < 0) return false;
        synchronized (ServerParallelItems.class) {
            List<Runnable> l = deferred[k];
            if (l == null) deferred[k] = l = new ArrayList<>(2);
            l.add(r);
        }
        return true;
    }

    public static LevelChunk chunk(int x, int z) {
        LevelChunk c = CHUNKS.get(ChunkPos.asLong(x, z));
        if (c == null) chunkMisses++;
        return c;
    }

    public static boolean deferMove(Entity e, EntityInLevelCallback cb) {
        if (!running) return false;
        // a run item cannot leave its section (its hull is inside one): a move here means the bound was wrong
        trap("storage move " + e.getClass().getSimpleName());
        return false;
    }

    public static boolean deferRemove(Entity e, EntityInLevelCallback cb, Entity.RemovalReason why) {
        if (!running) return false;
        Integer k = INDEX.get(e);
        if (k == null) {
            foreignRemoves++;
            trap("removal outside the run");
            return false;
        }
        synchronized (ServerParallelItems.class) {
            removedCb[k] = cb;
            removedWhy[k] = why;
        }
        return true;
    }

    /** a run tick did something the bounds said it could not: counted, the feature off from now on */
    public static void trap(String what) {
        if (!running) return;
        traps++;
        if (enabled) {
            enabled = false;
            System.out.println("[sleep/spi] TRAP (parallel items switched off): " + what + " on " + Thread.currentThread().getName());
            new Throwable("[sleep/spi] trap").printStackTrace(System.out);
        }
    }

    static void report() {
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "[sleep/spi] walks %d: runs %d, items in runs %d, levels per run %.1f; serial walk %.3f ms (%d), parallel walk %.3f ms (%d); deferred wakes %d, removes %d; traps %d, chunk misses %d; not apart:",
                walks, runs, inRuns, runs == 0 ? 0 : levelsSum / (double) runs, walksSerial == 0 ? 0 : nsSerial / 1e6 / walksSerial, walksSerial,
                walksParallel == 0 ? 0 : nsParallel / 1e6 / walksParallel, walksParallel, deferredWakes, deferredRemoves, traps, chunkMisses));
        NOT.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8).forEach(x -> sb.append(' ').append(x.getKey()).append('=').append(x.getValue()));
        System.out.println(sb);
    }
}
