package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * lab (notes/99, 1.3.6; -Dlab.opt.ServerParallelItemsMixin=true -Dlab.spec=true): runs of farm animals in the server's
 * entity walk ticked on the server pool. Stage 0 - the UPPER BOUND, not exact: ticks run in parallel with no conflict
 * handling (a push into a neighbour ticking at the same time races, a query sees neighbours before or after their tick).
 * What is already as the exact design will have it: the entity storage's moves and removals and the tick's effects
 * (sounds, game events, particles, entity events) are deferred and done after the run in each entity's order; chunk
 * lookups from workers read the published chunk map; vanilla's shared path type cache is read-only during the run, each
 * thread computes into an overlay merged after the run. {@code -Dlab.spec.alternate=true}: every other walk serial, the
 * two walk times reported ("[sleep/spec]").
 */
public final class SpecTicks {
    private SpecTicks() {}

    public static final boolean ON = Boolean.getBoolean("lab.spec");
    static final int MIN = Integer.getInteger("lab.spec.min", 64);
    static final int CHUNK = Integer.getInteger("lab.spec.chunk", 16);
    static final boolean ALTERNATE = Boolean.getBoolean("lab.spec.alternate");
    /** -Dlab.spec.items=false: dropped items tick serially in SpecTicks' walk (ServerParallelItems' runs off) */
    static final boolean ITEMS = !"false".equals(System.getProperty("lab.spec.items"));

    static {
        // (the transaction code out of line: inlined into vanilla's hot methods while runs tick, it slows the plain
        // ticks after them - JitHints; -Dlab.spec.jitHints=false: the JIT's own choice)
        if (ON && !"false".equals(System.getProperty("lab.spec.jitHints")))
            JitHints.dontInline("dev/lull/optsupport/sleep/SpecTx.*", "dev/lull/optsupport/sleep/SpecUndo.*", "dev/lull/optsupport/sleep/WB.hit");
    }

    private static volatile boolean running;
    private static Thread main;
    private static Entity[] all = new Entity[4096];
    private static Entity[] fastRest = new Entity[4096];
    static long fastGathered;
    private static int n;
    /**
     * this thread's SpecTicks state in one object - on our pool's threads a field of the thread (ThreadSlots.SPEC_LOCAL),
     * else a ThreadLocal: five ThreadLocal lookups per hook were 6 % of the run workers' samples (host JFR 11-23)
     */
    static final class Local {
        final int[] cur = {-1};
        final int[] job = new int[1];
        final Object[] lastChunks = new Object[8];
        final long[] lastPos = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, 0, -1};
        Long2ObjectOpenHashMap<PathType> overlay;

        /** per level (the path cache's positions array): this thread's path types across runs (lab.fast.pathMemo) */
        final java.util.IdentityHashMap<long[], Long2ObjectOpenHashMap<PathType>> memos = new java.util.IdentityHashMap<>();

        Long2ObjectOpenHashMap<PathType> memo(long[] cache) {
            Long2ObjectOpenHashMap<PathType> m = memos.get(cache);
            if (m == null) {
                memos.put(cache, m = new Long2ObjectOpenHashMap<>(4096));
                LOCALS.add(this);
            }
            return m;
        }

        Long2ObjectOpenHashMap<PathType> overlay() {
            Long2ObjectOpenHashMap<PathType> m = overlay;
            if (m == null) {
                overlay = m = new Long2ObjectOpenHashMap<>();
                OVERLAYS.add(m);
            }
            return m;
        }
    }

    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);
    /** the threads' states holding path memories (invalidated on the server thread with vanilla's cache) */
    private static final java.util.Set<Local> LOCALS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** lab.fast.pathMemo (on with lab.fast): path types kept per thread across runs, dropped where vanilla's cache drops one */
    public static final boolean PATH_MEMO = Boolean.getBoolean("lab.fast") && Boolean.getBoolean("lab.fast.pathMemo");   // (opt-in: host pigs30k 36.9 / 41.4 vs 36.4 / 36.8 ms with 279 M hits - no gain)
    static final int PATH_MEMO_MAX = Integer.getInteger("lab.fast.pathMemo.max", 1 << 16);
    static long memoHits, memoDrops;

    /** PathTypeCache.invalidate (the server thread: a block changed; runs' writes are applied after them): every thread's memory of it */
    public static void pathInvalidate(long[] cache, long key) {
        if (!PATH_MEMO) return;
        for (Local lo : LOCALS) {
            Long2ObjectOpenHashMap<PathType> m = lo.memos.get(cache);
            if (m != null && m.remove(key) != null) memoDrops++;
        }
    }

    static Local local() {
        if (Thread.currentThread() instanceof dev.lab.runtime.LabRuntime.LabThread t) {
            Object o = t.slots[ThreadSlots.SPEC_LOCAL];
            if (o == null) t.slots[ThreadSlots.SPEC_LOCAL] = o = new Local();
            return (Local) o;
        }
        return LOCAL.get();
    }
    /** depth of SpecTicks' pool jobs on this thread: the chunk hook serves these threads only (not other work on other pools) */

    static java.util.function.IntConsumer job(java.util.function.IntConsumer body) {
        return i -> {
            int[] d = local().job;
            d[0]++;
            try {
                body.accept(i);
            } finally {
                d[0]--;
            }
        };
    }

    /** this thread runs a SpecTicks job of a run (a worker's chunk lookups go to the run's published chunk map) */
    public static boolean inJob() {
        return running && local().job[0] > 0;
    }

    static boolean jobThread() {
        return local().job[0] > 0;
    }
    /** per walk index: what its tick deferred, in its order - an EntityInLevelCallback (a storage move), a Removal, a Runnable (an effect) */
    private static Object[][] deferred = new Object[4096][];
    private static int[] deferredN = new int[4096];
    static long walks, runs, inRuns, deferredMoves, deferredRemoves, deferredFx, chunkMisses, pathOverlay, nsSerial, nsParallel, walksSerial, walksParallel;

    record Removal(EntityInLevelCallback cb, Entity.RemovalReason why) {}

    public static void forEach(net.minecraft.world.level.entity.EntityTickList list, Consumer<Entity> consumer, ServerLevel lvl) {
        // (lab.fast: mods' tick listeners run under the bus's run lock - CompositeEventBus.RUN_POSTS - not a reason for a serial walk)
        if (!FAST && ParallelEntities.tickListenersActing()) {   // (an active profiler is fine: workers get the inactive one)
            list.forEach(consumer);
            return;
        }
        n = 0;
        list.forEach(e -> {
            if (n == all.length) all = java.util.Arrays.copyOf(all, n * 2);
            all[n++] = e;
        });
        walks++;
        if (FAST) {
            int tries = PATHS.getAndSet(0);
            if (PATH_BUDGET > 0) pathChance = Math.min(1f, PATH_BUDGET / (float) Math.max(1, tries));
            pathTicks++;
        }
        boolean serial = ALTERNATE && (walks & 1) == 0 || !HookFlags.parallelOk();
        long t0 = System.nanoTime();
        try {
            if (UNDO_TEST) {
                for (int k = 0; k < n; k++) if (eligible(all[k])) undoTest(k, consumer); else consumer.accept(all[k]);
                return;
            }
            if (TX_TEST) {
                for (int k = 0; k < n; k++) if (eligible(all[k])) txTest(k, consumer); else consumer.accept(all[k]);
                return;
            }
            int i = 0;
            if (FAST && !serial) {
                // lab.fast: the walk's eligible mobs gathered into one run (vanilla's order is not kept anyway): a dropped
                // item, an arrow or a creeper between them no longer cuts the run short of MIN - mixed worlds tick in
                // parallel too. The rest after it, in walk order (a stable partition through fastRest).
                if (fastRest.length < n) fastRest = new Entity[Math.max(n, fastRest.length * 2)];
                int e = 0, r = 0;
                for (int k = 0; k < n; k++) {
                    Entity x = all[k];
                    if (eligible(x)) all[e++] = x;
                    else fastRest[r++] = x;
                }
                System.arraycopy(fastRest, 0, all, e, r);
                java.util.Arrays.fill(fastRest, 0, r, null);
                if (e >= MIN) {
                    run(0, e, consumer, lvl);
                    fastGathered += e;
                    i = e;
                }
            }
            // (dropped items: ServerParallelItems' windows inside this walk - lab.spec.items, default on)
            boolean items = ITEMS && !serial && ServerParallelItems.enabled && lvl.getProfiler() == net.minecraft.util.profiling.InactiveProfiler.INSTANCE;
            if (items) ServerParallelItems.walkFrom(all, n);
            while (i < n) {
                if (items && all[i] instanceof net.minecraft.world.entity.item.ItemEntity) {
                    int j = i;
                    while (j < n && all[j] instanceof net.minecraft.world.entity.item.ItemEntity) j++;
                    i = ServerParallelItems.itemsWindow(i, j, consumer, lvl);
                    continue;
                }
                if (serial || !eligible(all[i])) {
                    consumer.accept(all[i++]);
                    continue;
                }
                int j = i;
                while (j < n && eligible(all[j])) j++;
                if (j - i < MIN) for (int k = i; k < j; k++) consumer.accept(all[k]);
                else if (EXACT) runExact(i, j, consumer, lvl);
                else run(i, j, consumer, lvl);
                i = j;
            }
        } finally {
            long dt = System.nanoTime() - t0;
            if (serial) { nsSerial += dt; walksSerial++; } else { nsParallel += dt; walksParallel++; }
            java.util.Arrays.fill(all, 0, n, null);
            if (walks % 40 == 0) report();
        }
    }

    // ------------------------------------------------------------------ test: a tick, undone, ticked again - the same?

    /**
     * -Dlab.spec.undoTest=true (serial, a test of SpecUndo): each eligible entity's state taken, the entity ticked (its
     * storage moves and effects kept aside, then dropped), its state taken again, the first state put back, the tick
     * run again (its moves and effects done for real): both results compared. A difference = state the undo misses
     * (or a tick that is not a function of its state). Ticks that saw another entity are counted apart (a push changed
     * the neighbour in the first run too).
     */
    static final boolean UNDO_TEST = Boolean.getBoolean("lab.spec.undoTest");
    private static boolean testing;
    private static final dev.lull.optsupport.sleep.SpecUndo.Snap S0 = new dev.lull.optsupport.sleep.SpecUndo.Snap(), S1 = new dev.lull.optsupport.sleep.SpecUndo.Snap(),
            S2 = new dev.lull.optsupport.sleep.SpecUndo.Snap();
    static long undoTicks, undoSame, undoDiff, undoNsTake, undoNsRestore, undoParts, undoContents;
    static final java.util.Map<String, Long> UNDO_DIFFS = new java.util.TreeMap<>();
    private static String described;

    private static void undoTest(int k, Consumer<Entity> consumer) {
        Entity e = all[k];
        if (deferred.length <= k) {
            int len = Math.max(k + 1, deferred.length * 2);
            deferred = java.util.Arrays.copyOf(deferred, len);
            deferredN = java.util.Arrays.copyOf(deferredN, len);
        }
        long t0 = System.nanoTime();
        dev.lull.optsupport.sleep.SpecUndo.take(e, S0);
        long t1 = System.nanoTime();
        undoNsTake += t1 - t0;
        undoParts += S0.parts();
        undoContents += S0.contents();
        if (described == null) System.out.println("[sleep/spec] undo test: a " + e.getType().toShortString() + "'s state: " + (described = dev.lull.optsupport.sleep.SpecUndo.describe(S0)));
        int[] cur = local().cur;
        testing = true;
        cur[0] = k;
        try {
            consumer.accept(e);
        } finally {
            cur[0] = -1;
            testing = false;
        }
        for (int q = 0; q < deferredN[k]; q++) deferred[k][q] = null;   // the first run's moves and effects: dropped
        deferredN[k] = 0;
        dev.lull.optsupport.sleep.SpecUndo.take(e, S1);
        long t2 = System.nanoTime();
        dev.lull.optsupport.sleep.SpecUndo.restore(S0);
        undoNsRestore += System.nanoTime() - t2;
        testing = true;
        cur[0] = k;
        try {
            consumer.accept(e);
        } finally {
            cur[0] = -1;
            testing = false;
        }
        dev.lull.optsupport.sleep.SpecUndo.take(e, S2);
        commitDeferred(k, k + 1);
        undoTicks++;
        String d = e.isRemoved() ? null : dev.lull.optsupport.sleep.SpecUndo.diff(S1, S2);
        if (d == null) undoSame++;
        else {
            undoDiff++;
            if (UNDO_DIFFS.merge(e.getType().toShortString() + " " + d, 1L, Long::sum) == 1 && UNDO_DIFFS.size() < 40)
                System.out.println("[sleep/spec] undo test: DIFFERENT after undo + tick again: " + e.getType().toShortString() + " " + d);
        }
    }

    /**
     * -Dlab.spec.txTest=true (serial, a test of SpecTx with the write barrier): each eligible entity's full state taken
     * (SpecUndo, the reference), the entity ticked inside a transaction (its moves and effects kept aside, dropped), the
     * transaction undone, the state taken again: it must equal the reference. Then the tick for real (no transaction);
     * every 2nd walk the real tick runs inside a transaction that is released (its cost, against the plain ticks).
     */
    static final boolean TX_TEST = Boolean.getBoolean("lab.spec.txTest");
    private static final dev.lull.optsupport.sleep.SpecTx TX0 = new dev.lull.optsupport.sleep.SpecTx();
    static long txTicks, txSame, txDiff, txObjs, txContents, nsTxTick, nsPlainTick, txTimed, plainTimed;
    static final java.util.Map<String, Long> TX_DIFFS = new java.util.TreeMap<>();

    private static void txTest(int k, Consumer<Entity> consumer) {
        Entity e = all[k];
        if (deferred.length <= k) {
            int len = Math.max(k + 1, deferred.length * 2);
            deferred = java.util.Arrays.copyOf(deferred, len);
            deferredN = java.util.Arrays.copyOf(deferredN, len);
        }
        dev.lull.optsupport.sleep.SpecUndo.take(e, S0);
        int[] cur = local().cur;
        testing = true;
        cur[0] = k;
        TX0.begin(e, k, false);
        dev.lull.optsupport.sleep.WB.TX = true;
        try {
            consumer.accept(e);
        } finally {
            dev.lull.optsupport.sleep.WB.TX = false;
            TX0.end();
            cur[0] = -1;
            testing = false;
        }
        for (int q = 0; q < deferredN[k]; q++) deferred[k][q] = null;
        deferredN[k] = 0;
        txObjs += TX0.objects();
        txContents += TX0.contentsCopied();
        TX0.restore();
        dev.lull.optsupport.sleep.SpecUndo.take(e, S1);
        txTicks++;
        String d = dev.lull.optsupport.sleep.SpecUndo.diff(S0, S1);
        if (d == null) txSame++;
        else {
            txDiff++;
            if (TX_DIFFS.merge(e.getType().toShortString() + " " + d, 1L, Long::sum) == 1 && TX_DIFFS.size() < 40)
                System.out.println("[sleep/spec] tx test: NOT UNDONE: " + e.getType().toShortString() + " " + d);
        }
        // the real tick: plain, or (every 2nd walk) inside a transaction - the barrier's cost
        boolean inTx = (walks & 1) == 0;
        long t0 = System.nanoTime();
        if (inTx) {
            testing = true;
            cur[0] = k;
            TX0.begin(e, k, false);
            dev.lull.optsupport.sleep.WB.TX = true;
            try {
                consumer.accept(e);
            } finally {
                dev.lull.optsupport.sleep.WB.TX = false;
                TX0.end();
                cur[0] = -1;
                testing = false;
            }
            TX0.release();
            nsTxTick += System.nanoTime() - t0;
            txTimed++;
            commitDeferred(k, k + 1);
        } else {
            consumer.accept(e);
            nsPlainTick += System.nanoTime() - t0;
            plainTimed++;
        }
    }

    /**
     * -Dlab.spec.monsters (default true): every goal-driven mob, not only animals - monsters path to players (players are
     * not run entities: reading them is fine), their shots / explosions / door breaking are world writes or spawns (the
     * tick stops there and runs again in order). Left out: raiders (the level's raid objects), the boss fights (dragon,
     * wither), shulkers (collidable: other entities' moves collide with them), brain-driven mobs (noBrain).
     */
    static final boolean MONSTERS = !"false".equals(System.getProperty("lab.spec.monsters"));

    static boolean kindOk(Entity e) {
        if (e instanceof Animal) return true;
        if (!MONSTERS || !(e instanceof net.minecraft.world.entity.Mob)) return false;
        return (FAST && HookFlags.guard("ServerParallelItemsRaidMixin", "raiders") || !(e instanceof net.minecraft.world.entity.raid.Raider)) && !(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)   // (lab.fast: raiders too - their raid's writes wait for the run's end)
                && !(e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) && !(e instanceof net.minecraft.world.entity.monster.Shulker);
    }

    static boolean eligible(Entity e) {
        return kindOk(e) && !e.isRemoved() && !e.isPassenger() && !e.isVehicle() && (e.getClass().getName().startsWith("net.minecraft.") || FAST_MOD_MOBS)
                && (!EXACT || dev.lull.optsupport.sleep.EntitySleep.plainNext(e) && noBrain(e)) && (!FAST || fastOk(e)) && (TYPES == null || TYPES.contains(e.getType().toShortString()));
    }

    /** lab.fast: not the mobs whose ticks reach shared state with no guard yet (points of interest, explosions); entity sleep's own ticks stay on the server thread */
    static boolean fastOk(Entity e) {
        return dev.lull.optsupport.sleep.EntitySleep.plainNext(e)   // (creepers too: their explosion waits for the run's end - ServerParallelItemsCreeperMixin)
                && (!(e instanceof net.minecraft.world.entity.monster.Creeper) || HookFlags.guard("ServerParallelItemsCreeperMixin", "creepers"));
    }

    /** lab.fast: the mobs that use points of interest (one shared, unguarded manager): all of a block on one thread */
    @SuppressWarnings("unchecked")
    private static final net.minecraft.world.entity.ai.memory.MemoryModuleType<? extends Entity>[] PARTNERS = new net.minecraft.world.entity.ai.memory.MemoryModuleType[] {
            net.minecraft.world.entity.ai.memory.MemoryModuleType.INTERACTION_TARGET, net.minecraft.world.entity.ai.memory.MemoryModuleType.BREED_TARGET};

    /** lab.fast.pathBudget=N: new paths a tick for mobs with no target (0: no limit, counted) */
    static final int PATH_BUDGET = Integer.getInteger("lab.fast.pathBudget", 0);
    private static final java.util.concurrent.atomic.AtomicInteger PATHS = new java.util.concurrent.atomic.AtomicInteger();
    static long pathsMade, pathsDenied, pathTicks;
    private static volatile float pathChance = 1f;

    /** a path for this mob now (lab.fast): always with a target, else within this tick's budget */
    public static boolean pathAllowed(net.minecraft.world.entity.Mob mob) {
        if (mob.getTarget() != null) {
            pathsMade++;
            return true;
        }
        PATHS.incrementAndGet();
        // (fair: each try is allowed with the chance budget / last tick's tries - not the first N of the walk's order,
        // which would leave the same mobs standing for good)
        if (PATH_BUDGET > 0 && running && pathChance < 1f && java.util.concurrent.ThreadLocalRandom.current().nextFloat() >= pathChance) {
            pathsDenied++;
            return false;
        }
        pathsMade++;
        return true;
    }

    static boolean usesPoi(Entity e) {
        return e instanceof net.minecraft.world.entity.npc.AbstractVillager || e instanceof net.minecraft.world.entity.animal.IronGolem
                || e instanceof net.minecraft.world.entity.animal.Bee;
    }

    /** lab.fast: a run tick's world write / spawn kept for the run's end (true), or not in a run (false: do it now) */
    public static boolean deferWorld(Runnable r) {
        if (!FAST || !running) return false;
        int k = current();
        if (k < 0) return false;
        add(k, r);
        fastDeferredWorld++;
        return true;
    }

    static long fastDeferredWorld, fastGroups, fastTargets, fastMaxGroup, fastBigSum, fastBlockSum, fastBlocks;

    /**
     * a mob driven by goals only: its brain holds no memories (a brain's behaviors, sensors and memory values are objects
     * outside the write barrier's roots - their undo is not covered yet)
     */
    /** -Dlab.spec.types=pig,cow,...: only these entity types in runs (bisecting) */
    static final java.util.Set<String> TYPES = System.getProperty("lab.spec.types") == null ? null : java.util.Set.of(System.getProperty("lab.spec.types").split(","));

    static boolean noBrain(Entity e) {
        return !(e instanceof net.minecraft.world.entity.LivingEntity le) || le.getBrain().getMemories().isEmpty();
    }

    // ------------------------------------------------------------------ stage 1: exact (transactions, validation, re-runs)

    /**
     * -Dlab.spec.exact (default with lab.spec; needs -Dlab.opt.WriteBarrier=true): a run is ticked in blocks of the
     * walk ({@code lab.spec.block} entities). A block's ticks run in parallel, each in a SpecTx (undo log, conflicts),
     * recording its entity queries and path type lookups; its storage moves, removals and effects are deferred. Then,
     * on the server thread: a tick is invalid when its transaction saw a conflict (a write into another tick's object
     * or another entity, a world write, a spawn, a draw from the level's random, an exception), when it holds a
     * reference to another entity of the run (goal targets: read through fields, unseen by the queries), or when one of
     * its query boxes meets the box another tick of the block swept (start to end of its tick: it may have seen that
     * one before or after its tick); a tick after an invalid one whose swept box an invalid one's query met is undone
     * too (the invalid one must see it as before its tick). All invalid ticks are undone (latest first); then in walk
     * order: a valid tick's path type lookups are replayed into the shared cache (a value that differs, or a shared
     * entry vanilla would have recomputed: invalid after all - undone), its deferred work done; an invalid tick runs
     * again on the server thread inside a serial transaction - if that run's queries meet a later tick's swept box or it
     * writes into a later tick's objects, it is undone with those later ticks and runs again. Exact by construction: a
     * committed tick read nothing another tick of the block changed, an invalid one runs in vanilla's order.
     */
    /**
     * -Dlab.fast=true (1.4, notes/101, NOT exact): the runs ticked on the pool without transactions - groups of touching
     * mobs (and a mob with the mob it targets) on one thread each, the runs' moves / removals / effects / block writes /
     * spawns kept for the run's end in walk order; reads of other groups see them mid-tick. Mobs that use points of
     * interest (villagers, iron golems, bees) and creepers (explosions) tick the usual way.
     */
    public static final boolean FAST = Boolean.getBoolean("lab.fast");
    /** lab.fast.modMobs (off, its own settings row): mods' mobs in runs too - their own code may share state with no guard */
    static final boolean FAST_MOD_MOBS = FAST && Boolean.getBoolean("lab.fast.modMobs");
    /** lab.fast: the POI manager under one lock during runs (ServerParallelItemsPoiLockMixin) - villagers / golems not one group */
    public static final boolean POI_LOCK = FAST && !"false".equals(System.getProperty("lab.fast.poiLock"));

    /** the POI lock in force: its three parts applied (the queries' lock, sectionsToVillage, the deferred reputation events) */
    static boolean poiLock() {
        return POI_LOCK && HookFlags.guard("ServerParallelItemsPoiLockMixin", "villagers and golems (one thread for the users of points of interest)")
                && HookFlags.guard("ServerParallelItemsPoiMixin", "villagers and golems (one thread for the users of points of interest)")
                && HookFlags.guard("ServerParallelItemsReputationMixin", "villagers and golems (one thread for the users of points of interest)");
    }
    public static final Object POI = new Object();
    /** measurement only (unsafe: the POI manager is not thread-safe): -Dlab.fast.noPoiGroup=true */
    static final boolean NO_POI_GROUP = Boolean.getBoolean("lab.fast.noPoiGroup");
    /** lab.fast.aiLod=N (prototype): mobs far from players pick goals / sense every N-th tick (ServerParallelItemsAiLodMixin) */
    public static final int AI_LOD = FAST ? Integer.getInteger("lab.fast.aiLod", 0) : 0;
    public static volatile long aiLodSkips;
    /** lab.fast: Sleepium's own random streams (FastRandom) for server entities and per thread for the level (-Dlab.fast.random=false: vanilla's, the level's behind a lock) */
    public static final boolean FAST_RANDOM = FAST && !"false".equals(System.getProperty("lab.fast.random"));
    /** lab.fast.entityRandom (off): server entities' own random as FastRandom too - no CAS per draw, but mob sleep cannot look ahead in it (it predicts vanilla's LCG) and refuses those mobs */
    public static final boolean FAST_ENTITY_RANDOM = FAST_RANDOM && Boolean.getBoolean("lab.fast.entityRandom");
    /** -Dlab.fast.contact=true: lab.fast keeps touching mobs on one thread too (groups by contact, as exact mode does) */
    static final boolean FAST_CONTACT = Boolean.getBoolean("lab.fast.contact");
    public static final boolean EXACT = !Boolean.getBoolean("lab.spec.unsafe") && !FAST;
    /** the shared path type cache's index mask: vanilla's 4096 entries, lab.fast 65536 (ServerParallelItemsPathMixin) */
    public static final int PATH_MASK = FAST && Boolean.getBoolean("lab.fast.bigPathCache") ? 65535 : 4095;   // (opt-in: host pigs30k 34.2 / 36.0 vs 33.5 / 34.0 ms - no gain)
    /** the write barrier is woven in (lab.opt.WriteBarrier): WB may be touched - without it WB's class cannot load (no watch word); lab.fast runs without it */
    public static final boolean WB_ON = ON && Boolean.getBoolean("lab.opt.WriteBarrier");
    static final int BLOCK = Math.min(4096, Integer.getInteger("lab.spec.block", 2048));   // (the grid packs a slot in 12 bits)
    private static dev.lull.optsupport.sleep.SpecTx[] txs = new dev.lull.optsupport.sleep.SpecTx[0];
    private static double[] sweep = new double[0];   // per slot: the swept box (6)
    private static boolean[] invalid = new boolean[0], done = new boolean[0];
    /** groups: slots whose start boxes (inflated by MARGIN) meet; per slot its group (the group's first slot), the
     * groups' slots in walk order (order[], from groupStart[g] to groupStart[g + 1]) */
    static final double MARGIN = Double.parseDouble(System.getProperty("lab.spec.margin", "1.0"));
    private static int[] groupOf = new int[0], parent = new int[0], order = new int[0], groupStart = new int[0], groupIx = new int[0];
    private static boolean[] sectionMoved = new boolean[0];
    /** per slot: its tick looked up path types (only those are replayed) */
    private static boolean[] hasPaths = new boolean[0];
    /** the block being committed: a slot below commitCursor is committed (SpecTx.COMMITTED) */
    private static int commitB0, commitCursor;

    static boolean committed(dev.lull.optsupport.sleep.SpecTx tx) {
        int slot = tx.index - commitB0;
        return slot >= 0 && slot < commitCursor && !invalid[slot];
    }
    private static int nGroups;
    /** a new value per block: the entities' slots (SpecSlot) of earlier blocks read as none */
    private static volatile long slotEpoch;
    static long groupedTicks, groupsMulti;
    static long blocks, specTicks, invalidTicks, cascadeTicks, reruns, pathInvalid, refInvalid, spatialInvalid, txInvalid;
    static final java.util.Map<String, Long> WHY = new java.util.concurrent.ConcurrentHashMap<>();

    static long nsGroups, nsReplay, nsDeferredWork;
    /** bisecting switches (default on): undo groups in parallel; committed ticks released after the commit loop */
    static final boolean PAR_UNDO = !"false".equals(System.getProperty("lab.spec.parUndo"));
    static final boolean LATE_RELEASE = !"false".equals(System.getProperty("lab.spec.lateRelease"));
    /**
     * an invalid tick undoes its whole group (default). -Dlab.spec.suffix=true: only the group's ticks after it - NOT exact
     * yet (pig pens differ, 11-22; the hole is not found), kept for the search.
     */
    static final boolean WHOLE_GROUP = !Boolean.getBoolean("lab.spec.suffix");

    private static int find(int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /** the block's groups: union of slots whose start boxes, inflated by MARGIN, meet (grid of 4-block cells) */
    static long nsgInit, nsgGrid, nsgPairs, nsgOrder, nsgSlots;

    private static void groups(int b0, int m) {
        long ga = System.nanoTime();
        long ep = ++slotEpoch;
        // the start boxes and the entities' slots, in parallel (per slot, nothing shared)
        ServerReactor.pool().forRange(m, 128, ServerReactor.pool().workerCount(), job(i -> {
            parent[i] = i;
            Entity en = all[b0 + i];
            net.minecraft.world.phys.AABB bb = en.getBoundingBox();
            int o = i * 6;
            sweep[o] = bb.minX - MARGIN; sweep[o + 1] = bb.minY - MARGIN; sweep[o + 2] = bb.minZ - MARGIN;
            sweep[o + 3] = bb.maxX + MARGIN; sweep[o + 4] = bb.maxY + MARGIN; sweep[o + 5] = bb.maxZ + MARGIN;
            ((SpecSlot) en).sleep$setSpecSlot(i, ep);
        }));
        long gb = System.nanoTime();
        // (lab.fast: no groups by contact - a push between two threads' mobs is lost or doubled at worst, a plausible
        // world; a pen of 500 animals would be one thread's whole work otherwise. Only a mob and its target stay together.)
        if (!FAST || FAST_CONTACT) buildGrid(m);
        else nUsed = 0;
        long gc = System.nanoTime();
        double[] s = sweep;
        for (int ub = 0; ub < nUsed; ub++) {
            int b = used[ub];
            for (int x = head[b]; x >= 0; x = next[x]) {
                long key = cells[x] & ~0xFFFL;
                int i = (int) (cells[x] & 0xFFF), oi = i * 6;
                for (int y = next[x]; y >= 0; y = next[y]) {
                    if ((cells[y] & ~0xFFFL) != key) continue;
                    int j = (int) (cells[y] & 0xFFF), oj = j * 6;
                    if (s[oi] <= s[oj + 3] && s[oi + 3] >= s[oj] && s[oi + 1] <= s[oj + 4] && s[oi + 4] >= s[oj + 1] && s[oi + 2] <= s[oj + 5] && s[oi + 5] >= s[oj + 2]) {
                        int ra = find(i), rb = find(j);
                        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
                    }
                }
            }
        }
        boolean poiLock = poiLock();
        if (FAST && !NO_POI_GROUP) {   // (the users of points of interest: one thread for all of them)
            int p0 = -1;
            for (int i = 0; i < m; i++)
                if (poiLock ? all[b0 + i] instanceof net.minecraft.world.entity.animal.Bee : usesPoi(all[b0 + i])) {   // (with the POI lock: bees only - their hives' occupant lists)
                    if (p0 < 0) p0 = i;
                    else {
                        int ra = find(i), rb = find(p0);
                        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
                    }
                }
        }
        if (poiLock)   // (brain partners write into each other - gossip, trades, breeding: both on one thread)
            for (int i = 0; i < m; i++)
                if (all[b0 + i] instanceof net.minecraft.world.entity.LivingEntity le && le.getBrain() != null) {
                    var br = le.getBrain();
                    for (var mt : PARTNERS) {
                        if (!br.hasMemoryValue(mt)) continue;
                        Object o = br.getMemory(mt).orElse(null);
                        if (!(o instanceof Entity t)) continue;
                        int j = ((SpecSlot) t).sleep$specSlot(ep);
                        if (j >= 0 && j < m && all[b0 + j] == t) {
                            int ra = find(i), rb = find(j);
                            if (ra != rb) {
                                parent[Math.max(ra, rb)] = Math.min(ra, rb);
                                fastTargets++;
                            }
                        }
                    }
                }
        if (FAST)   // (a mob hits / pushes what it targets: both on one thread)
            for (int i = 0; i < m; i++)
                if (all[b0 + i] instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() != null) {
                    net.minecraft.world.entity.LivingEntity t = mob.getTarget();
                    int j = ((SpecSlot) t).sleep$specSlot(ep);
                    if (j >= 0 && j < m && all[b0 + j] == t) {
                        int ra = find(i), rb = find(j);
                        if (ra != rb) {
                            parent[Math.max(ra, rb)] = Math.min(ra, rb);
                            fastTargets++;
                        }
                    }
                }
        long gd = System.nanoTime();
        // group ids by first slot; slots ordered by group, walk order inside
        int[] count = groupIx;   // (reused: count per root, then the group index per slot)
        java.util.Arrays.fill(count, 0, m, 0);
        for (int i = 0; i < m; i++) {
            int r = find(i);
            groupOf[i] = r;
            count[r]++;
        }
        int g = 0, pos = 0;
        int[] startOfRoot = parent;   // (parent no longer needed: reuse as the root's group index)
        for (int i = 0; i < m; i++) {
            if (groupOf[i] != i) continue;
            groupStart[g] = pos;
            pos += count[i];
            startOfRoot[i] = g++;
        }
        groupStart[g] = pos;
        nGroups = g;
        if (FAST) {   // (stats: the largest group of a block - one thread ticks it all)
            int big = 0;
            for (int i = 0; i < g; i++) big = Math.max(big, groupStart[i + 1] - groupStart[i]);
            fastMaxGroup = Math.max(fastMaxGroup, big);
            fastBigSum += big;
            fastBlockSum += m;
            fastBlocks++;
        }
        int[] fill = count;
        for (int i = 0; i < g; i++) fill[i] = groupStart[i];   // (count no longer needed: next free place per group)
        for (int i = 0; i < m; i++) {
            int gi = startOfRoot[groupOf[i]];
            order[fill[gi]++] = i;
        }
        for (int i = 0; i < m; i++) groupIx[i] = startOfRoot[groupOf[i]];
        for (int i = 0; i < g; i++) if (groupStart[i + 1] - groupStart[i] > 1) { groupsMulti++; groupedTicks += groupStart[i + 1] - groupStart[i]; }
        long ge = System.nanoTime();
        groupB0 = b0;
        long gf = System.nanoTime();
        nsgInit += gb - ga; nsgGrid += gc - gb; nsgPairs += gd - gc; nsgOrder += ge - gd; nsgSlots += gf - ge;
    }

    private static int groupB0;
    /** per slot: another tick of the block wrote into this one's entity */
    private static volatile boolean[] hitBy = new boolean[0];

    static void victim(Entity e) {
        int slot = ((SpecSlot) e).sleep$specSlot(slotEpoch);
        if (slot >= 0 && all[groupB0 + slot] == e) hitBy[slot] = true;
    }

    /** an entity's group in the current block (-1: not one of its ticks) */
    static int groupOfEntity(Entity e) {
        int slot = ((SpecSlot) e).sleep$specSlot(slotEpoch);
        return slot < 0 || all[groupB0 + slot] != e ? -1 : groupOf[slot];
    }

    private static void runExact(int from, int to, Consumer<Entity> consumer, ServerLevel lvl) {
        if (deferred.length < to) {
            int len = Math.max(to, deferred.length * 2);
            deferred = java.util.Arrays.copyOf(deferred, len);
            deferredN = java.util.Arrays.copyOf(deferredN, len);
        }
        runs++;
        inRuns += to - from;
        chunkEpoch++;
        main = Thread.currentThread();
        dev.lull.optsupport.sleep.SpecTx.mainThread = main;
        pathCache = lvl.getPathTypeCache();
        dev.lull.optsupport.sleep.SpecTx.levelSeed = levelSeed(lvl);
        for (int b = from; b < to; b += BLOCK) {
            int b1 = Math.min(to, b + BLOCK), key = Math.min(KEYS - 1, b / BLOCK);
            boolean ser = FORCE_PLAIN || walks > SPEC_WALKS || ADAPT && (walks < denseUntil[key] || serialNext(key, b1 - b));
            long t = System.nanoTime();
            if (ser) serialBlock(b, b1, consumer);
            else if (!block(b, b1, consumer)) {   // (dense: most of its ticks in huge groups - plain ticks)
                serialBlock(b, b1, consumer);
                denseBlocks++;
                denseUntil[key] = walks + 16;   // (plain for the next 16 walks without building its groups again)
                ser = true;
            }
            float per = (System.nanoTime() - t) / (float) (b1 - b);
            float[] ema = ser ? serNs : specNs;
            // (a re-try is a fresh measurement of the loser: it replaces the old one)
            ema[key] = ema[key] == 0 || retry[key] ? per : ema[key] * 0.75f + per * 0.25f;
            if (ser) serialTicks += b1 - b;
        }
    }

    // adaptive: per block of the walk (by its place), the measured cost a tick of speculating it and of ticking it
    // plainly in walk order; the cheaper one is taken, the other re-tried now and then (dense pens / farms where most
    // speculative ticks are undone run plainly, open herds in parallel)
    static final boolean ADAPT = !"false".equals(System.getProperty("lab.spec.adapt"));
    private static final int KEYS = 256;
    private static final float[] specNs = new float[KEYS], serNs = new float[KEYS];
    /** per key: when the loser is tried next (nanoTime), the interval to the try after it */
    private static final long[] tryAt = new long[KEYS], tryEvery = new long[KEYS];
    private static final long TRY_FIRST = 10_000_000_000L, TRY_MAX = 640_000_000_000L;
    /** per key: the last block was a re-try of the loser; the winner before it */
    private static final boolean[] retry = new boolean[KEYS], wasSerial = new boolean[KEYS];
    static long serialTicks, serialBlocks, denseBlocks;
    private static long plainWithTx;
    /** -Dlab.spec.forcePlain (measurement): every block ticked plainly */
    static final boolean FORCE_PLAIN = Boolean.getBoolean("lab.spec.forcePlain");
    /** -Dlab.spec.specWalks=N (measurement): after N walks every block plainly (does speculation slow plain ticks later?) */
    static final long SPEC_WALKS = Long.getLong("lab.spec.specWalks", Long.MAX_VALUE);
    private static final long[] denseUntil = new long[KEYS];
    /** a group of more than DENSE ticks is huge (a packed pen, a crowd): one thread, undone as a whole */
    static final int DENSE = Integer.getInteger("lab.spec.dense", 64);

    private static boolean serialNext(int key, int m) {
        float sp = specNs[key], se = serNs[key];
        if (sp == 0) return false;                      // (speculation measured first)
        if (se == 0) return lastInvalidShare > 0.25f;   // (plain ticks measured once speculation undid much)
        boolean ser = se < sp;
        // the loser re-tried 10 s later, then twice as late after each re-try it lost again (up to ~10 min): a re-try
        // costs the gap once - a long tick where speculation loses badly (by walks it came 3x as often: every level's
        // walk counts; the farm paid ~3 % for its re-tries, 11-23)
        long now = System.nanoTime();
        if (retry[key]) {
            retry[key] = false;
            tryEvery[key] = ser == wasSerial[key] ? Math.min(TRY_MAX, Math.max(TRY_FIRST, tryEvery[key] * 2)) : TRY_FIRST;
            tryAt[key] = now + tryEvery[key];
        }
        if (tryAt[key] == 0) tryAt[key] = now + TRY_FIRST;
        if (now >= tryAt[key]) {
            retry[key] = true;
            wasSerial[key] = ser;
            return !ser;
        }
        return ser;
    }

    /** the last speculated block's share of undone ticks */
    private static float lastInvalidShare;

    private static void serialBlock(int b0, int b1, Consumer<Entity> consumer) {
        serialBlocks++;
        var ctx = dev.lull.optsupport.sleep.SpecTx.current();
        if (WB_ON && dev.lull.optsupport.sleep.WB.TX || ctx != null && ctx.active) {
            if (plainWithTx++ < 3) System.out.println("[sleep/spec] a plain block with transactions on: TX " + dev.lull.optsupport.sleep.WB.TX + ", running " + running
                    + ", the thread's transaction " + (ctx == null ? "none" : "active " + ctx.active + " serial " + ctx.serial + " index " + ctx.index) + ", " + Thread.currentThread().getName());
        }
        for (int k = b0; k < b1; k++) consumer.accept(all[k]);
    }

    private static Object levelSeed(ServerLevel lvl) {
        try {
            if (seedField == null) {
                seedField = net.minecraft.world.level.levelgen.LegacyRandomSource.class.getDeclaredField("seed");
                seedField.setAccessible(true);
            }
            return lvl.random instanceof net.minecraft.world.level.levelgen.LegacyRandomSource r ? seedField.get(r) : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static java.lang.reflect.Field seedField;

    /** the current block's size */
    private static int curM;

    /** a block speculated; false: not (most of its ticks in huge groups - nothing ran, the caller ticks it plainly) */
    /** the per-slot arrays for a block of m */
    private static void ensure(int m) {
        curM = m;
        if (txs.length >= m && sweep.length >= m * 6) return;
        int old = txs.length;
        if (txs.length < m) {
            txs = java.util.Arrays.copyOf(txs, m);
            for (int i = old; i < m; i++) txs[i] = new dev.lull.optsupport.sleep.SpecTx();
        }
        sweep = new double[m * 6];
        invalid = new boolean[m];
        done = new boolean[m];
        groupOf = new int[m];
        parent = new int[m];
        order = new int[m];
        groupStart = new int[m + 1];
        groupIx = new int[m];
        sectionMoved = new boolean[m];
        hasPaths = new boolean[m];
    }

    private static boolean block(int b0, int b1, Consumer<Entity> consumer) {
        int m = b1 - b0;
        curM = m;
        if (txs.length < m) {
            int old = txs.length;
            txs = java.util.Arrays.copyOf(txs, m);
            for (int i = old; i < m; i++) txs[i] = new dev.lull.optsupport.sleep.SpecTx();
            sweep = new double[m * 6];
            invalid = new boolean[m];
            done = new boolean[m];
            groupOf = new int[m];
            parent = new int[m];
            order = new int[m];
            groupStart = new int[m + 1];
            groupIx = new int[m];
            sectionMoved = new boolean[m];
            hasPaths = new boolean[m];
        }
        java.util.Arrays.fill(invalid, 0, m, false);
        java.util.Arrays.fill(done, 0, m, false);
        var pool = ServerReactor.pool();
        long tg = System.nanoTime();
        groups(b0, m);
        long t0 = System.nanoTime();
        nsGroups += t0 - tg;
        if (ADAPT) {
            int huge = 0;
            for (int g = 0; g < nGroups; g++) {
                int size = groupStart[g + 1] - groupStart[g];
                if (size > DENSE) huge += size;
            }
            if (huge * 2 > m) return false;
        }
        blocks++;
        specTicks += m;
        dev.lull.optsupport.sleep.SpecTx.GROUP = SpecTicks::groupOfEntity;
        dev.lull.optsupport.sleep.SpecTx.VICTIM = SpecTicks::victim;
        dev.lull.optsupport.sleep.SpecTx.COMMITTED = SpecTicks::committed;
        commitB0 = b0;
        commitCursor = 0;
        if (hitBy.length < m) hitBy = new boolean[m];
        java.util.Arrays.fill(hitBy, 0, m, false);
        dev.lull.optsupport.sleep.WB.TX = true;
        running = true;
        try {
            pool.forRange(nGroups, 4, pool.workerCount(), job(g -> {
                for (int a = groupStart[g]; a < groupStart[g + 1]; a++) speculate(b0, order[a], consumer);
            }));
        } finally {
            running = false;
            dev.lull.optsupport.sleep.WB.TX = false;
        }
        long t1 = System.nanoTime();
        nsPhase += t1 - t0;
        validate(b0, m);
        long t2 = System.nanoTime();
        // undo every invalid tick, each group's latest first - groups in parallel (no object is in the logs of two
        // groups: a write into another group's claimed object is a conflict that copies nothing; the section indexes
        // were marked for rebuilding during the run, so the box re-set off the owner thread changes nothing there)
        if (!PAR_UNDO) for (int i = m - 1; i >= 0; i--) { if (invalid[i]) undo(b0, i); }
        else {
            ServerReactor.pool().forRange(nGroups, 8, ServerReactor.pool().workerCount(), job(g -> {
                for (int a = groupStart[g + 1] - 1; a >= groupStart[g]; a--) {
                    int i = order[a];
                    if (invalid[i]) txs[i].restoreFields();
                }
            }));
            for (int i = m - 1; i >= 0; i--) if (invalid[i]) undoRest(b0, i);   // (contents in walk order, then the rest)
        }
        long t3 = System.nanoTime();
        nsUndo += t3 - t2;
        long rr = 0;
        dev.lull.optsupport.sleep.WB.TX = true;
        try {
            for (int i = 0; i < m; i++) {
                int k = b0 + i;
                commitCursor = i;
                long tp0 = System.nanoTime();
                boolean pathOk = invalid[i] || !hasPaths[i] || replayPaths(txs[i]);
                nsReplay += System.nanoTime() - tp0;
                if (!pathOk) {
                    pathInvalid++;
                    why("path type cache");
                    int g = groupIx[i];   // (it and the rest of its group, latest first)
                    for (int a = groupStart[g + 1] - 1; a >= groupStart[g]; a--) {
                        int j = order[a];
                        if (done[j] || invalid[j]) continue;
                        invalid[j] = true;
                        undo(b0, j);
                    }
                }
                if (invalid[i]) {
                    long r0 = System.nanoTime();
                    rerun(b0, m, i, consumer);
                    rr += System.nanoTime() - r0;
                } else {
                    if (deferredN[k] > 0) {
                        long td0 = System.nanoTime();
                        commitDeferred(k, k + 1);
                        nsDeferredWork += System.nanoTime() - td0;
                    }
                    if (!LATE_RELEASE) txs[i].release();
                }
                done[i] = true;
            }
            commitCursor = m;
        } finally {
            dev.lull.optsupport.sleep.WB.TX = false;
        }
        // the committed ticks' transactions released together (a re-run reads their words as stale: COMMITTED)
        if (LATE_RELEASE) {
            var rpool = ServerReactor.pool();
            rpool.forRange(m, 64, rpool.workerCount(), job(i -> {
                if (!invalid[i]) txs[i].release();
            }));
        }
        commitCursor = 0;
        int inv = 0;
        for (int i = 0; i < m; i++) if (invalid[i]) inv++;
        lastInvalidShare = inv / (float) m;
        nsRerun += rr;
        nsCommitRest += System.nanoTime() - t3 - rr;
        nsCommit += System.nanoTime() - t1;
        return true;
    }

    private static void speculate(int b0, int q, Consumer<Entity> consumer) {
        int k = b0 + q;
        Entity e = all[k];
        dev.lull.optsupport.sleep.SpecTx tx = txs[q];
        net.minecraft.world.phys.AABB bb = e.getBoundingBox();
        int[] cur = local().cur;
        cur[0] = k;
        tx.begin(e, k, false);
        tx.group = groupOf[q];
        long sec = net.minecraft.core.SectionPos.asLong(e.blockPosition());
        if (holdsOthers(e)) tx.conflict("holds another entity");
        try {
            consumer.accept(e);   // (exceptions: ServerParallelItemsGuardMixin makes them conflicts)
            if (holdsOthers(e)) tx.conflict("holds another entity");
        } catch (Throwable t) {
            tx.conflict("threw " + t);
        } finally {
            tx.end();
            cur[0] = -1;
        }
        net.minecraft.world.phys.AABB eb = e.getBoundingBox();
        sectionMoved[q] = net.minecraft.core.SectionPos.asLong(e.blockPosition()) != sec;
        hasPaths[q] = tx.np > 0;
        double[] s = sweep;
        int o = q * 6;
        s[o] = Math.min(bb.minX, eb.minX); s[o + 1] = Math.min(bb.minY, eb.minY); s[o + 2] = Math.min(bb.minZ, eb.minZ);
        s[o + 3] = Math.max(bb.maxX, eb.maxX); s[o + 4] = Math.max(bb.maxY, eb.maxY); s[o + 5] = Math.max(bb.maxZ, eb.maxZ);
    }

    /**
     * the spatial grid: cells of 4 blocks (x, z); per covered cell of a slot's box one entry (cell << 12 | slot), sorted -
     * a cell's slots are a run of the array (no maps, no lists)
     */
    private static long[] cells = new long[8192];
    private static int nCells;

    private static long ck(int x, int z) {
        return ((long) (x & 0x7FFFFF) << 35) | ((long) (z & 0x7FFFFF) << 12);
    }

    // a hash grid: entry t = cells[t] (cell << 12 | slot), chained per bucket (next[t]); a bucket is current when its
    // stamp is this build's generation (no clearing between builds)
    private static int[] next = new int[8192], head = new int[1 << 14], stamp = new int[1 << 14], used = new int[1 << 14];
    private static int gen, nUsed;
    private static final int HMASK = (1 << 14) - 1;

    private static int bucket(long key) {
        return (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & HMASK;
    }

    /** the first entry of cell key's bucket chain (-1: none) - entries of other cells in the chain are skipped by the caller */
    private static int chain(long key) {
        int b = bucket(key);
        return stamp[b] == gen ? head[b] : -1;
    }

    /** which slots' boxes (sweep) cover each cell */
    private static void buildGrid(int m) {
        nCells = 0;
        nUsed = 0;
        gen++;
        double[] s = sweep;
        for (int i = 0; i < m; i++) {
            int o = i * 6;
            int x0 = (int) Math.floor(s[o]) >> 2, z0 = (int) Math.floor(s[o + 2]) >> 2, x1 = (int) Math.floor(s[o + 3]) >> 2, z1 = (int) Math.floor(s[o + 5]) >> 2;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    if (nCells == cells.length) {
                        cells = java.util.Arrays.copyOf(cells, nCells * 2);
                        next = java.util.Arrays.copyOf(next, nCells * 2);
                    }
                    long key = ck(x, z);
                    int t = nCells++, b = bucket(key);
                    cells[t] = key | i;
                    if (stamp[b] != gen) {
                        stamp[b] = gen;
                        head[b] = -1;
                        used[nUsed++] = b;
                    }
                    next[t] = head[b];
                    head[b] = t;
                }
        }
    }

    /** the slots (other than i) whose swept boxes meet a query box of tx; later ones only: {@code laterThan} >= 0 */
    private static void touched(dev.lull.optsupport.sleep.SpecTx tx, int i, int laterThan, it.unimi.dsi.fastutil.ints.IntArrayList out) {
        double[] s = sweep;
        double[] qb = tx.q;
        for (int a = 0; a < tx.nq; a += 6) {
            double x0 = qb[a], y0 = qb[a + 1], z0 = qb[a + 2], x1 = qb[a + 3], y1 = qb[a + 4], z1 = qb[a + 5];
            int cx0 = (int) Math.floor(x0) >> 2, cz0 = (int) Math.floor(z0) >> 2, cx1 = (int) Math.floor(x1) >> 2, cz1 = (int) Math.floor(z1) >> 2;
            if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 4096) {   // a huge query: every slot
                for (int j = 0; j < curM; j++) if (j != i && j > laterThan && !out.contains(j)) out.add(j);
                continue;
            }
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++) {
                    long key = ck(cx, cz);
                    for (int t = chain(key); t >= 0; t = next[t]) {
                        if ((cells[t] & ~0xFFFL) != key) continue;
                        int j = (int) (cells[t] & 0xFFF);
                        if (j == i || j <= laterThan) continue;
                        int o = j * 6;
                        if (s[o] <= x1 && s[o + 3] >= x0 && s[o + 1] <= y1 && s[o + 4] >= y0 && s[o + 2] <= z1 && s[o + 5] >= z0 && !out.contains(j)) out.add(j);
                    }
                }
        }
    }

    private static final it.unimi.dsi.fastutil.ints.IntArrayList HIT = new it.unimi.dsi.fastutil.ints.IntArrayList();

    private static boolean[] spatial = new boolean[0];
    private static final ThreadLocal<it.unimi.dsi.fastutil.ints.IntArrayList> HITS = ThreadLocal.withInitial(it.unimi.dsi.fastutil.ints.IntArrayList::new);
    static long nsGrid, nsValidate, nsUndo, nsRerun, nsCommitRest;

    private static void validate(int b0, int m) {
        long t0 = System.nanoTime();
        buildGrid(m);
        long t1 = System.nanoTime();
        nsGrid += t1 - t0;
        if (spatial.length < m) spatial = new boolean[m];
        var pool = ServerReactor.pool();
        pool.forRange(m, 64, pool.workerCount(), job(i -> {   // (read-only: the grid, the boxes, the query logs)
            it.unimi.dsi.fastutil.ints.IntArrayList h = HITS.get();
            h.clear();
            touched(txs[i], i, -1, h);
            boolean bad = false;
            for (int n2 = 0; n2 < h.size() && !bad; n2++) {
                int j = h.getInt(n2);
                // a tick of its own group ran in walk order next to it (fine) - unless an earlier one changed section:
                // its storage move was deferred, a query of this one may have missed it there
                bad = groupOf[j] != groupOf[i] || j < i && sectionMoved[j];
            }
            spatial[i] = bad;
        }));
        for (int i = 0; i < m; i++) {
            dev.lull.optsupport.sleep.SpecTx tx = txs[i];
            if (tx.conflict) {
                invalid[i] = true;
                txInvalid++;
                why(tx.why);
            } else if (spatial[i]) {
                invalid[i] = true;
                spatialInvalid++;
                why("saw another tick's box");
            } else if (hitBy[i]) {
                invalid[i] = true;
                spatialInvalid++;
                why("another tick wrote into it");
            }
        }
        nsValidate += System.nanoTime() - t1;
        // a group is undone as a whole (its ticks read each other's results); an invalid tick's queries that meet a
        // later valid tick: that one's group too (it must see that one as before its tick) - until nothing changes
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < m; i++) {
                if (!invalid[i]) continue;
                int g = groupIx[i];
                for (int a = groupStart[g]; a < groupStart[g + 1]; a++) {
                    int j = order[a];
                    if ((j > i || WHOLE_GROUP) && !invalid[j]) {   // (the group's ticks after it read its result; the ones before did not)
                        invalid[j] = true;
                        changed = true;
                        cascadeTicks++;
                        why("an earlier tick of its group is undone");
                    }
                }
                HIT.clear();
                touched(txs[i], i, i, HIT);
                for (int n2 = 0; n2 < HIT.size(); n2++) {
                    int j = HIT.getInt(n2);
                    if (!invalid[j]) {
                        invalid[j] = true;
                        changed = true;
                        cascadeTicks++;
                        why("seen by an earlier invalid tick");
                    }
                }
            }
        }
        for (int i = 0; i < m; i++) if (invalid[i]) invalidTicks++;
    }

    private static void why(String w) {
        WHY.merge(w == null ? "?" : w.length() > 80 ? w.substring(0, 80) : w, 1L, Long::sum);
    }

    /** slot i's speculative result undone (its transaction restored, its deferred work dropped) */
    private static void undo(int b0, int i) {
        txs[i].restore();
        undoTail(b0, i);
    }

    /** the parallel undo's serial part: contents, release, then the rest of undo */
    private static void undoRest(int b0, int i) {
        txs[i].restoreContentsAndRelease();
        undoTail(b0, i);
    }

    private static void undoTail(int b0, int i) {
        int k = b0 + i;
        // the box came back past setBoundingBox: set it again the usual way (the same value) so that what follows a box
        // change on the owner thread runs - the entity section indexes' update (no game state changes)
        Entity ue = all[k];
        if (ue != null) ue.setBoundingBox(ue.getBoundingBox());
        Object[] d = deferred[k];
        for (int q = 0; q < deferredN[k]; q++) d[q] = null;
        deferredN[k] = 0;
    }

    /** slot i again, on the server thread in walk order (serial transaction; later ticks it touches undone first) */
    private static void rerun(int b0, int m, int i, Consumer<Entity> consumer) {
        int k = b0 + i;
        Entity e = all[k];
        dev.lull.optsupport.sleep.SpecTx tx = txs[i];
        int[] cur = local().cur;
        for (int round = 0; ; round++) {
            reruns++;
            tx.begin(e, k, true);
            testing = true;
            cur[0] = k;
            try {
                consumer.accept(e);
            } finally {
                tx.end();
                cur[0] = -1;
                testing = false;
            }
            HIT.clear();
            touched(tx, i, i, HIT);
            for (int n2 = HIT.size() - 1; n2 >= 0; n2--) if (done[HIT.getInt(n2)] || invalid[HIT.getInt(n2)]) HIT.removeInt(n2);
            for (dev.lull.optsupport.sleep.SpecTx into : tx.cascade)
                for (int j = i + 1; j < m; j++) if (txs[j] == into && !invalid[j] && !done[j] && !HIT.contains(j)) HIT.add(j);
            if (HIT.isEmpty()) {
                commitDeferred(k, k + 1);
                tx.release();
                return;
            }
            // it read (or wrote) later ticks' results: undo it and them (the latest first), then again
            undo(b0, i);
            it.unimi.dsi.fastutil.ints.IntArrays.quickSort(HIT.elements(), 0, HIT.size());
            for (int n2 = HIT.size() - 1; n2 >= 0; n2--) {
                int h = HIT.getInt(n2), g = groupIx[h];
                for (int a = groupStart[g + 1] - 1; a >= groupStart[g]; a--) {   // (it and its group's ticks after it, latest first)
                    int j = order[a];
                    if (j < h && !WHOLE_GROUP || invalid[j] || done[j]) continue;
                    undo(b0, j);
                    invalid[j] = true;
                    cascadeTicks++;
                    why("touched by a re-run");
                }
            }
            if (round > 64) throw new IllegalStateException("[sleep/spec] re-run of " + e + " does not settle");
        }
    }

    /**
     * slot's path type lookups into the shared cache in walk order: a shared entry it used must be what vanilla would
     * find now (else vanilla would have recomputed or seen another value: false); its own computes stored as vanilla
     * stores them.
     */
    private static boolean replayPaths(dev.lull.optsupport.sleep.SpecTx tx) {
        if (tx.np == 0 || pathCache == null) return true;
        var acc = (dev.lull.opt.ServerParallelItemsPathAccessor) pathCache;
        long[] positions = acc.sleep$positions();
        PathType[] types = acc.sleep$pathTypes();
        for (int a = 0; a < tx.np; a++) {
            long key = tx.pathKey[a];
            int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & PATH_MASK;
            PathType live = positions[idx] == key ? types[idx] : null;
            if (live != null) {
                if (live != tx.pathVal[a]) return false;   // (vanilla would have used this one)
            } else if (tx.pathKind[a] != dev.lull.optsupport.sleep.SpecTx.COMPUTED) return false;   // (vanilla would have recomputed: not checkable here)
            else {
                positions[idx] = key;
                types[idx] = tx.pathVal[a];
            }
        }
        return true;
    }

    /** a goal (or the target) of the mob holds another entity that is not a player: read through a field, not a query */
    private static final ClassValue<long[]> ENTITY_FIELDS = new ClassValue<>() {
        @Override
        protected long[] computeValue(Class<?> c) {
            it.unimi.dsi.fastutil.longs.LongArrayList l = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (java.lang.reflect.Field fd : k.getDeclaredFields())
                    if (!java.lang.reflect.Modifier.isStatic(fd.getModifiers()) && Entity.class.isAssignableFrom(fd.getType())) l.add(UNSAFE.objectFieldOffset(fd));
            return l.toLongArray();
        }
    };
    private static final sun.misc.Unsafe UNSAFE;

    static {
        sun.misc.Unsafe u = null;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException ignored) {
        }
        UNSAFE = u;
    }

    static boolean holdsOthers(Entity e) {
        if (!(e instanceof net.minecraft.world.entity.Mob m)) return false;
        if (other(e, m.getTarget()) || other(e, m.getLastHurtByMob())) return true;
        // (a goal reads its held entity while it runs; one that is not running holds at most what it found by a query)
        for (net.minecraft.world.entity.ai.goal.WrappedGoal g : m.goalSelector.getAvailableGoals()) if (g.isRunning() && holds(e, g.getGoal())) return true;
        for (net.minecraft.world.entity.ai.goal.WrappedGoal g : m.targetSelector.getAvailableGoals()) if (g.isRunning() && holds(e, g.getGoal())) return true;
        return false;
    }

    private static boolean other(Entity self, Object o) {
        return o instanceof Entity en && en != self && !(en instanceof net.minecraft.world.entity.player.Player);
    }

    private static boolean holds(Entity self, Object goal) {
        long[] offs = ENTITY_FIELDS.get(goal.getClass());
        for (int i = 0; i < offs.length; i++) {
            Object o = UNSAFE.getObject(goal, offs[i]);
            if (o != null && o != self && other(self, o)) return true;
        }
        return false;
    }

    public static long quietEvents;

    /**
     * vanilla's ServerLevel.gameEvent would do nothing: no listener of NeoForge's VanillaGameEvent (our bus says so) and
     * no game event listener registered in a section within the event's radius (GameEventDispatcher.post's own walk:
     * the registries do not change while a run ticks - their owners are not run entities, block entities do not move)
     */
    public static boolean quietGameEvent(ServerLevel level, net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> ev, net.minecraft.world.phys.Vec3 pos) {
        if (!(net.neoforged.neoforge.common.NeoForge.EVENT_BUS instanceof CompositeEventBus cb)
                || cb.mayHaveListeners(net.neoforged.neoforge.event.VanillaGameEvent.class)) return false;
        int r = ev.value().notificationRadius();
        BlockPos p = BlockPos.containing(pos);
        int x0 = net.minecraft.core.SectionPos.blockToSectionCoord(p.getX() - r), y0 = net.minecraft.core.SectionPos.blockToSectionCoord(p.getY() - r);
        int z0 = net.minecraft.core.SectionPos.blockToSectionCoord(p.getZ() - r), x1 = net.minecraft.core.SectionPos.blockToSectionCoord(p.getX() + r);
        int y1 = net.minecraft.core.SectionPos.blockToSectionCoord(p.getY() + r), z1 = net.minecraft.core.SectionPos.blockToSectionCoord(p.getZ() + r);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                LevelChunk c = level.getChunkSource().getChunkNow(x, z);
                if (c == null) continue;
                // (not getListenerRegistry: it creates a missing registry in the chunk's map - a write from many threads;
                // a missing one is an empty one to vanilla's dispatch)
                var regs = ((dev.lull.opt.ServerParallelItemsChunkListenersAccessor) c).sleep$listenerSections();
                for (int y = y0; y <= y1; y++) {
                    var reg = regs.get(y);
                    if (reg != null && !reg.isEmpty()) return false;
                }
            }
        quietEvents++;
        return true;
    }

    /** a world write or spawn during a speculative tick: a conflict, the tick stops there (SpecAbort) */
    public static void abortIfSpeculative(String what) {
        dev.lull.optsupport.sleep.SpecTx tx = dev.lull.optsupport.sleep.SpecTx.current();
        if (tx == null || !tx.active || tx.serial) return;
        tx.conflict(what);
        throw dev.lull.optsupport.sleep.SpecTx.SpecAbort.INSTANCE;
    }

    private static void run(int from, int to, Consumer<Entity> consumer, ServerLevel lvl) {
        if (deferred.length < to) {
            int len = Math.max(to, deferred.length * 2);
            deferred = java.util.Arrays.copyOf(deferred, len);
            deferredN = java.util.Arrays.copyOf(deferredN, len);
        }
        runs++;
        inRuns += to - from;
        chunkEpoch++;
        main = Thread.currentThread();
        pathCache = lvl.getPathTypeCache();
        var pool = ServerReactor.pool();
        long t0 = System.nanoTime();
        running = true;
        try {
            if (FAST) {
                for (int b = from; b < to; b += BLOCK) {
                    int b0 = b, m = Math.min(to, b + BLOCK) - b;
                    ensure(m);
                    groups(b0, m);
                    fastGroups += nGroups;
                    pool.forRange(nGroups, 4, pool.workerCount(), job(g -> {
                        int[] cur = local().cur;
                        for (int a = groupStart[g]; a < groupStart[g + 1]; a++) {
                            int k = b0 + order[a];
                            cur[0] = k;
                            try {
                                consumer.accept(all[k]);
                            } finally {
                                cur[0] = -1;
                            }
                        }
                    }));
                }
            } else pool.forRange(to - from, CHUNK, pool.workerCount(), job(q -> {
                int k = from + q;
                int[] cur = local().cur;
                cur[0] = k;
                try {
                    consumer.accept(all[k]);
                } finally {
                    cur[0] = -1;
                }
            }));
        } finally {
            running = false;
        }
        long t1 = System.nanoTime();
        nsPhase += t1 - t0;
        mergePaths();
        if (FAST) FastPush.drain();
        commitDeferred(from, to);
        nsCommit += System.nanoTime() - t1;
    }

    // ------------------------------------------------------------------ lab.fast: chunk ticks in parallel

    /**
     * lab.fast (-Dlab.fast.chunkTicks=false: serial): ServerLevel.tickChunk - thunder, ice and snow, random ticks - for all
     * ticking chunks on the pool after the spawning walk instead of chunk by chunk inside it. Each chunk is a walk index:
     * its world writes, spawns, effects, neighbour updates and scheduled ticks are kept and done at the pass's end in
     * the chunks' order (reads see the world as it was before the pass); mods' blocks' random ticks are kept whole and
     * done there too (their code may use the mod's own state). A chunk whose tick throws on the pool is done again
     * serially, its kept work dropped.
     */
    public static volatile boolean chunkTicks = FAST && !"false".equals(System.getProperty("lab.fast.chunkTicks"));
    private static LevelChunk[] ctChunks = new LevelChunk[256];
    private static boolean[] ctFailed = new boolean[256];
    private static int ctN, ctSpeed;
    private static ServerLevel ctLevel;
    static long ctPasses, ctTicked, ctRedone, ctModTicks, ctNs, fastEmptyChunks;

    static {
        if (FAST) dev.lull.bots.BotLoad.AB.put("chunkTicks", v -> chunkTicks = v);
    }

    /** ServerParallelItemsChunkTicksMixin: the chunk's tickChunk kept for the pass (true), or done now (false) */
    public static boolean keepChunkTick(ServerLevel lvl, LevelChunk c, int speed) {
        if (!chunkTicks || running || ServerParallelItems.running() || !HookFlags.parallelOk()) return false;
        if (!HookFlags.guard("ServerParallelItemsRandomTickMixin", "chunk ticks") || !HookFlags.guard("ServerParallelItemsScheduleMixin", "chunk ticks")
                || !HookFlags.guard("ServerParallelItemsNeighborAccessor", "chunk ticks")) return false;
        if (ctLevel != null && ctLevel != lvl) chunkTicksPass();
        if (ctN == ctChunks.length) ctChunks = java.util.Arrays.copyOf(ctChunks, ctN * 2);
        ctChunks[ctN++] = c;
        ctLevel = lvl;
        ctSpeed = speed;
        return true;
    }

    /** ServerParallelItemsRandomTickMixin: a mod's block's random tick during the pass, kept whole for its end (true) */
    public static boolean keepModRandomTick(Class<?> code, Runnable tick) {
        if (!running || current() < 0 || VANILLA_CODE.get(code)) return false;
        ctModTicks++;
        return deferWorld(tick);
    }

    private static final ClassValue<Boolean> VANILLA_CODE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return c.getName().startsWith("net.minecraft.");
        }
    };

    /** ServerParallelItemsChunkTicksMixin, before the custom spawners: the kept chunks ticked */
    public static void chunkTicksPass() {
        int n = ctN;
        if (n == 0) return;
        ServerLevel lvl = ctLevel;
        int speed = ctSpeed;
        LevelChunk[] cs = ctChunks;
        ctN = 0;
        ctLevel = null;
        long t0 = System.nanoTime();
        if (deferred.length < n) {
            int len = Math.max(n, deferred.length * 2);
            deferred = java.util.Arrays.copyOf(deferred, len);
            deferredN = java.util.Arrays.copyOf(deferredN, len);
        }
        if (ctFailed.length < n) ctFailed = new boolean[Math.max(n, ctFailed.length * 2)];
        boolean[] failed = ctFailed;
        java.util.Arrays.fill(failed, 0, n, false);
        ctPasses++;
        ctTicked += n;
        chunkEpoch++;
        main = Thread.currentThread();
        var pool = ServerReactor.pool();
        var real = ((dev.lull.opt.ServerParallelItemsNeighborAccessor) lvl).sleep$neighborUpdater();
        ((dev.lull.opt.ServerParallelItemsNeighborAccessor) lvl).sleep$setNeighborUpdater(new DeferredNeighbors(real));
        running = true;
        try {
            pool.forRange(n, 4, pool.workerCount(), job(i -> {
                int[] cur = local().cur;
                cur[0] = i;
                try {
                    lvl.tickChunk(cs[i], speed);
                } catch (Throwable t) {
                    failed[i] = true;
                } finally {
                    cur[0] = -1;
                }
            }));
        } finally {
            running = false;
            ((dev.lull.opt.ServerParallelItemsNeighborAccessor) lvl).sleep$setNeighborUpdater(real);
        }
        for (int i = 0; i < n; i++) {
            if (!failed[i]) continue;
            for (int q = 0; q < deferredN[i]; q++) deferred[i][q] = null;
            deferredN[i] = 0;
        }
        commitDeferred(0, n);
        for (int i = 0; i < n; i++) {
            if (!failed[i]) continue;
            ctRedone++;
            lvl.tickChunk(cs[i], speed);
        }
        java.util.Arrays.fill(cs, 0, n, null);
        ctNs += System.nanoTime() - t0;
    }

    /** the level's neighbour updates during a chunk ticks pass: kept for its end */
    static final class DeferredNeighbors implements net.minecraft.world.level.redstone.NeighborUpdater {
        final net.minecraft.world.level.redstone.NeighborUpdater real;

        DeferredNeighbors(net.minecraft.world.level.redstone.NeighborUpdater real) {
            this.real = real;
        }

        private void keep(Runnable r) {
            if (!deferWorld(r)) r.run();
        }

        @Override
        public void shapeUpdate(net.minecraft.core.Direction d, net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.BlockPos pos,
                                net.minecraft.core.BlockPos from, int flags, int left) {
            net.minecraft.core.BlockPos p = pos.immutable(), f = from.immutable();
            keep(() -> real.shapeUpdate(d, state, p, f, flags, left));
        }

        @Override
        public void neighborChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block, net.minecraft.core.BlockPos from) {
            net.minecraft.core.BlockPos p = pos.immutable(), f = from.immutable();
            keep(() -> real.neighborChanged(p, block, f));
        }

        @Override
        public void neighborChanged(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block,
                                    net.minecraft.core.BlockPos from, boolean moved) {
            net.minecraft.core.BlockPos p = pos.immutable(), f = from.immutable();
            keep(() -> real.neighborChanged(state, p, block, f, moved));
        }

        @Override
        public void updateNeighborsAtExceptFromFacing(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block, net.minecraft.core.Direction except) {
            net.minecraft.core.BlockPos p = pos.immutable();
            keep(() -> real.updateNeighborsAtExceptFromFacing(p, block, except));
        }
    }

    /** what the ticks of [from, to) deferred, in the walk's order */
    private static void commitDeferred(int from, int to) {
        for (int k = from; k < to; k++) {
            Object[] d = deferred[k];
            int m = deferredN[k];
            for (int q = 0; q < m; q++) {
                Object o = d[q];
                d[q] = null;
                if (o instanceof EntityInLevelCallback cb) {
                    deferredMoves++;
                    cb.onMove();
                } else if (o instanceof Removal r) {
                    deferredRemoves++;
                    r.cb.onRemove(r.why);
                } else {
                    deferredFx++;
                    ((Runnable) o).run();
                }
            }
            deferredN[k] = 0;
        }
    }

    static long nsPhase, nsCommit;
    private static long lastPhase, lastCommit, lastPar, lastWalksPar, lastRuns;

    private static void add(int k, Object o) {
        Object[] d = deferred[k];
        int m = deferredN[k];
        if (d == null) deferred[k] = d = new Object[4];
        else if (m == d.length) deferred[k] = d = java.util.Arrays.copyOf(d, m * 2);
        d[m] = o;
        deferredN[k] = m + 1;
    }

    /** the walk index this thread is ticking in a run, or -1 */
    private static int current() {
        return running || testing ? local().cur[0] : -1;
    }

    // ------------------------------------------------------------------ hooks

    public static boolean running() {
        return running;
    }

    public static boolean onWorker() {
        return running && Thread.currentThread() != main;
    }

    /** Entity.setPosRaw's levelCallback.onMove() during a run: kept for after it (a repeat of the last one dropped) */
    /** a run tick on this thread (lab.fast's hooks) */
    public static boolean inRun() {
        return running && local().cur[0] >= 0;
    }

    /** lab.fast: a move that stays in its section - vanilla's onMove does nothing for it (no deferral, no call) */
    public static boolean deferMove(Entity e, EntityInLevelCallback cb) {
        if (FAST && current() >= 0 && cb instanceof dev.lull.opt.ServerParallelItemsSectionKeyAccessor a
                && a.sleep$sectionKey() == net.minecraft.core.SectionPos.asLong(e.blockPosition())) {
            sameSectionMoves++;
            return true;
        }
        return deferMove(cb);
    }

    static long sameSectionMoves;

    public static boolean deferMove(EntityInLevelCallback cb) {
        int k = current();
        if (k < 0) return false;
        if (EXACT) {
            dev.lull.optsupport.sleep.SpecTx tx = dev.lull.optsupport.sleep.SpecTx.current();
            if (tx != null && tx.active && !tx.serial) tx.moved(all[k]);
        }
        int m = deferredN[k];
        if (m == 0 || deferred[k][m - 1] != cb) add(k, cb);
        return true;
    }

    public static boolean deferRemove(EntityInLevelCallback cb, Entity.RemovalReason why) {
        int k = current();
        if (k < 0) return false;
        add(k, new Removal(cb, why));
        return true;
    }

    /** an effect of a run tick (a sound, game event, particles, entity / level / damage event): done after the run */
    public static boolean deferFx(Runnable r) {
        int k = current();
        if (k < 0) return false;
        add(k, r);
        return true;
    }

    /** a worker's chunk lookup during a run: the published chunk map, no blocking, no shared lookup cache */
    /** per worker: its last chunks (position, chunk) - the server thread's lookup cache is its own */
    /** a new value per run: a worker's remembered chunks are good for one run only (chunks unload between ticks) */
    private static volatile long chunkEpoch;

    /** a new value per run (FrozenGrid: a grid is good for its run) */
    static long runEpoch() {
        return chunkEpoch;
    }

    public static LevelChunk chunk(ServerChunkCache cache, int x, int z, boolean require) {
        long key = ChunkPos.asLong(x, z);
        long[] lp = local().lastPos;
        Object[] lc0 = local().lastChunks;
        long ep = chunkEpoch;
        if (lp[5] != ep) {
            lp[0] = lp[1] = lp[2] = lp[3] = Long.MIN_VALUE;
            java.util.Arrays.fill(lc0, null);
            lp[5] = ep;
        }
        for (int q = 0; q < 4; q++) if (lp[q] == key && lc0[q] instanceof LevelChunk hit && lc0[q + 4] == cache) return hit;
        ChunkHolder h = cache.chunkMap.getVisibleChunkIfPresent(key);
        ChunkAccess c = h == null ? null : h.getChunkIfPresent(ChunkStatus.FULL);
        if (c instanceof LevelChunk lc) {
            int slot = (int) (lp[4]++ & 3);
            lp[slot] = key;
            lc0[slot] = lc;
            lc0[slot + 4] = cache;
            return lc;
        }
        chunkMisses++;
        if (require && EXACT) {
            abortIfSpeculative("needs an unloaded chunk");
            throw new IllegalStateException("[sleep/spec] a run tick needs chunk " + x + ", " + z + " that is not loaded");
        }
        if (require && FAST) {   // (lab.fast: vanilla would load it on the server thread; a worker reads it as empty, as a client does - a mob at the loaded edge strolling outward stopped the server)
            fastEmptyChunks++;
            return new net.minecraft.world.level.chunk.EmptyLevelChunk(cache.level, new ChunkPos(x, z),
                    cache.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
        }
        if (require) throw new IllegalStateException("[sleep/spec] a run tick needs chunk " + x + ", " + z + " that is not loaded");
        return null;
    }

    // ------------------------------------------------------------------ vanilla's path type cache: read-only in a run, overlays

    private static net.minecraft.world.level.pathfinder.PathTypeCache pathCache;
    private static final List<Long2ObjectOpenHashMap<PathType>> OVERLAYS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** PathTypeCache.getOrCompute during a run (any thread): this thread's computes, else the shared entry, else computed */
    public static PathType pathType(long[] positions, PathType[] types, BlockGetter level, BlockPos pos) {
        if (EXACT) {
            long key = pos.asLong();
            dev.lull.optsupport.sleep.SpecTx tx = dev.lull.optsupport.sleep.SpecTx.current();
            if (tx == null || !tx.active) return dev.lull.opt.ServerParallelItemsWalkInvoker.sleep$pathTypeFromState(level, pos);   // (not a run tick)
            int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & PATH_MASK;
            Object own = tx.ownSlot(idx, key);
            PathType t;
            if (own instanceof PathType ot) {   // (its own compute, still in the slot: vanilla's hit)
                tx.path(key, ot, dev.lull.optsupport.sleep.SpecTx.OWN);
                return ot;
            }
            if (own == dev.lull.optsupport.sleep.SpecTx.NONE && positions[idx] == key && (t = types[idx]) != null) {
                tx.path(key, t, dev.lull.optsupport.sleep.SpecTx.SHARED);
                return t;
            }
            t = dev.lull.opt.ServerParallelItemsWalkInvoker.sleep$pathTypeFromState(level, pos);   // (vanilla's miss)
            tx.ownPut(idx, key, t);
            tx.path(key, t, dev.lull.optsupport.sleep.SpecTx.COMPUTED);
            return t;
        }
        long key = pos.asLong();
        Local lo = local();
        if (PATH_MEMO) {   // (lab.fast: this thread's memory of the level's path types, across runs - PathMemo)
            Long2ObjectOpenHashMap<PathType> memo = lo.memo(positions);
            PathType t = memo.get(key);
            if (t != null) {
                memoHits++;
                return t;
            }
            t = dev.lull.opt.ServerParallelItemsWalkInvoker.sleep$pathTypeFromState(level, pos);
            if (memo.size() >= PATH_MEMO_MAX) memo.clear();
            memo.put(key, t);
            return t;
        }
        Long2ObjectOpenHashMap<PathType> ov = lo.overlay();
        PathType t = ov.get(key);
        if (t != null) return t;
        int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & PATH_MASK;
        if (positions[idx] == key && (t = types[idx]) != null) return t;
        t = dev.lull.opt.ServerParallelItemsWalkInvoker.sleep$pathTypeFromState(level, pos);
        ov.put(key, t);
        return t;
    }

    /** stage 0: the overlays' entries written into the shared cache (in no particular order) */
    private static void mergePaths() {
        if (pathCache == null) return;
        var acc = (dev.lull.opt.ServerParallelItemsPathAccessor) pathCache;
        long[] positions = acc.sleep$positions();
        PathType[] types = acc.sleep$pathTypes();
        for (Long2ObjectOpenHashMap<PathType> ov : OVERLAYS) {
            for (Long2ObjectMap.Entry<PathType> en : ov.long2ObjectEntrySet()) {
                long key = en.getLongKey();
                int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & PATH_MASK;
                positions[idx] = key;
                types[idx] = en.getValue();
                pathOverlay++;
            }
            ov.clear();
        }
    }

    private static long txSum(boolean copies) {
        long n = 0;
        for (dev.lull.optsupport.sleep.SpecTx t : txs) n += copies ? t.copiesMine() : t.contentsMine();
        return n;
    }

    static void report() {
        if (EXACT && blocks > 0) System.out.println(String.format(java.util.Locale.ROOT,
                "[sleep/spec] exact: live words met outside runs %d; blocks %d (plain %d: %d ticks, %d of them dense), ticks %d (in groups of 2+: %d in %d), invalid %d (%.2f %%: transaction %d, spatial %d, cascade %d, path cache %d), re-runs %d; ms a walk: groups %.2f [init %.2f grid %.2f pairs %.2f order %.2f slots %.2f] (path replay %.2f, deferred work %.2f), grid %.2f, validate %.2f, undo %.2f, re-runs %.2f, commit rest %.2f; copies %d, contents %d, restores %d; why %s",
                dev.lull.optsupport.sleep.WB.liveOutside, blocks, serialBlocks, serialTicks, denseBlocks, specTicks, groupedTicks, groupsMulti, invalidTicks, specTicks == 0 ? 0 : 100.0 * invalidTicks / specTicks, txInvalid, spatialInvalid, cascadeTicks, pathInvalid, reruns,
                nsGroups / 1e6 / Math.max(1, walks), nsgInit / 1e6 / Math.max(1, walks), nsgGrid / 1e6 / Math.max(1, walks), nsgPairs / 1e6 / Math.max(1, walks),
                nsgOrder / 1e6 / Math.max(1, walks), nsgSlots / 1e6 / Math.max(1, walks), nsReplay / 1e6 / Math.max(1, walks), nsDeferredWork / 1e6 / Math.max(1, walks), nsGrid / 1e6 / Math.max(1, walks), nsValidate / 1e6 / Math.max(1, walks), nsUndo / 1e6 / Math.max(1, walks), nsRerun / 1e6 / Math.max(1, walks), nsCommitRest / 1e6 / Math.max(1, walks),
                txSum(true), txSum(false), dev.lull.optsupport.sleep.SpecTx.restores, WHY));
        if (TX_TEST && txTicks > 0) System.out.println(String.format(java.util.Locale.ROOT,
                "[sleep/spec] tx test: %d ticks, undone %d, NOT UNDONE %d; %.1f objects copied, %.1f contents a tick; tick in a transaction %.0f ns vs plain %.0f ns; writes %d, conflicts %d; mutation sites %d (missed %d); not undone: %s",
                txTicks, txSame, txDiff, txObjs / (double) txTicks, txContents / (double) txTicks, nsTxTick / (double) Math.max(1, txTimed), nsPlainTick / (double) Math.max(1, plainTimed),
                dev.lull.optsupport.sleep.SpecTx.writes, dev.lull.optsupport.sleep.SpecTx.conflicts, dev.lull.optsupport.sleep.WriteBarrier.mutationSites, dev.lull.optsupport.sleep.WriteBarrier.mutationMisses, TX_DIFFS));
        if (UNDO_TEST && undoTicks > 0) System.out.println(String.format(java.util.Locale.ROOT,
                "[sleep/spec] undo test: %d ticks, same %d, DIFFERENT %d; take %.0f ns, restore %.0f ns a tick; %.1f parts, %.1f contents a state; restored parts %d, contents %d; overflows %d; differences %s",
                undoTicks, undoSame, undoDiff, undoNsTake / (double) undoTicks, undoNsRestore / (double) undoTicks, undoParts / (double) undoTicks, undoContents / (double) undoTicks,
                dev.lull.optsupport.sleep.SpecUndo.restoredParts, dev.lull.optsupport.sleep.SpecUndo.restoredContents, dev.lull.optsupport.sleep.SpecUndo.overflows, UNDO_DIFFS) + "; " + dev.lull.optsupport.sleep.SpecUndo.stats());
        long dw = walksParallel - lastWalksPar;
        if (dw > 0) System.out.println(String.format(java.util.Locale.ROOT, "[sleep/spec] last %d parallel walks: walk %.2f ms, of it the runs' parallel phase %.2f ms, their commit %.2f ms (%d runs)",
                dw, (nsParallel - lastPar) / 1e6 / dw, (nsPhase - lastPhase) / 1e6 / dw, (nsCommit - lastCommit) / 1e6 / dw, runs - lastRuns));
        lastPhase = nsPhase; lastCommit = nsCommit; lastPar = nsParallel; lastWalksPar = walksParallel; lastRuns = runs;
        if (FAST && ctPasses > 0) {
            System.out.println(String.format(java.util.Locale.ROOT, "[sleep/spec] lab.fast chunk ticks: %d passes, %.1f chunks a pass, %.3f ms a pass, %d done again serially, %d mods' random ticks kept; unloaded chunks read as empty %d",
                    ctPasses, ctTicked / (double) ctPasses, ctNs / 1e6 / ctPasses, ctRedone, ctModTicks, fastEmptyChunks));
        }
        if (FAST && fastBlocks > 0) {
            System.out.println(String.format(java.util.Locale.ROOT, "[sleep/spec] fast: blocks %d, groups %d (%.1f a block), mobs a block %.0f, its largest group %.0f on average (max %d), targets joined %d, world writes deferred %d, gathered %d; pushes summed %d (applied %d), moves in their section %d; frozen grids %d, queries %d, visited %d; paths %.1f a tick, denied %.1f a tick; path memo hits %d, drops %d",
                    fastBlocks, fastGroups, fastGroups / (double) fastBlocks, fastBlockSum / (double) fastBlocks, fastBigSum / (double) fastBlocks, fastMaxGroup, fastTargets, fastDeferredWorld, fastGathered, FastPush.pushes, FastPush.applied, sameSectionMoves, FrozenGrid.builds, FrozenGrid.queries, FrozenGrid.visited, pathsMade / (double) Math.max(1, pathTicks), pathsDenied / (double) Math.max(1, pathTicks), memoHits, memoDrops));
            fastMaxGroup = 0;
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[sleep/spec] walks %d: runs %d, entities in runs %d (%.0f a walk); serial walk %.3f ms (%d), parallel walk %.3f ms (%d); deferred moves %d, removes %d, effects %d; path overlay entries %d, chunk misses %d; workers %d",
                walks, runs, inRuns, walks == 0 ? 0 : inRuns / (double) walks, walksSerial == 0 ? 0 : nsSerial / 1e6 / walksSerial, walksSerial,
                walksParallel == 0 ? 0 : nsParallel / 1e6 / walksParallel, walksParallel, deferredMoves, deferredRemoves, deferredFx, pathOverlay, chunkMisses,
                ServerReactor.pool().workerCount()));
    }
}
