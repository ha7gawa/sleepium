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

    static {
        // (the transaction code out of line: inlined into vanilla's hot methods while runs tick, it slows the plain
        // ticks after them - JitHints; -Dlab.spec.jitHints=false: the JIT's own choice)
        if (ON && !"false".equals(System.getProperty("lab.spec.jitHints")))
            JitHints.dontInline("dev/lull/optsupport/sleep/SpecTx.*", "dev/lull/optsupport/sleep/SpecUndo.*", "dev/lull/optsupport/sleep/WB.hit");
    }

    private static volatile boolean running;
    private static Thread main;
    private static Entity[] all = new Entity[4096];
    private static int n;
    private static final ThreadLocal<int[]> CUR = ThreadLocal.withInitial(() -> new int[] {-1});
    /** per walk index: what its tick deferred, in its order - an EntityInLevelCallback (a storage move), a Removal, a Runnable (an effect) */
    private static Object[][] deferred = new Object[4096][];
    private static int[] deferredN = new int[4096];
    static long walks, runs, inRuns, deferredMoves, deferredRemoves, deferredFx, chunkMisses, pathOverlay, nsSerial, nsParallel, walksSerial, walksParallel;

    record Removal(EntityInLevelCallback cb, Entity.RemovalReason why) {}

    public static void forEach(net.minecraft.world.level.entity.EntityTickList list, Consumer<Entity> consumer, ServerLevel lvl) {
        if (ParallelEntities.tickListenersActing()) {   // (an active profiler is fine: workers get the inactive one)
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
            if (UNDO_TEST) {
                for (int k = 0; k < n; k++) if (eligible(all[k])) undoTest(k, consumer); else consumer.accept(all[k]);
                return;
            }
            if (TX_TEST) {
                for (int k = 0; k < n; k++) if (eligible(all[k])) txTest(k, consumer); else consumer.accept(all[k]);
                return;
            }
            int i = 0;
            while (i < n) {
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
        int[] cur = CUR.get();
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
        int[] cur = CUR.get();
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
        return !(e instanceof net.minecraft.world.entity.raid.Raider) && !(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)
                && !(e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) && !(e instanceof net.minecraft.world.entity.monster.Shulker);
    }

    static boolean eligible(Entity e) {
        return kindOk(e) && !e.isRemoved() && !e.isPassenger() && !e.isVehicle() && e.getClass().getName().startsWith("net.minecraft.")
                && (!EXACT || dev.lull.optsupport.sleep.EntitySleep.plainNext(e) && noBrain(e)) && (TYPES == null || TYPES.contains(e.getType().toShortString()));
    }

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
    public static final boolean EXACT = !Boolean.getBoolean("lab.spec.unsafe");
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
        ServerReactor.pool().forRange(m, 128, ServerReactor.pool().workerCount(), i -> {
            parent[i] = i;
            Entity en = all[b0 + i];
            net.minecraft.world.phys.AABB bb = en.getBoundingBox();
            int o = i * 6;
            sweep[o] = bb.minX - MARGIN; sweep[o + 1] = bb.minY - MARGIN; sweep[o + 2] = bb.minZ - MARGIN;
            sweep[o + 3] = bb.maxX + MARGIN; sweep[o + 4] = bb.maxY + MARGIN; sweep[o + 5] = bb.maxZ + MARGIN;
            ((SpecSlot) en).sleep$setSpecSlot(i, ep);
        });
        long gb = System.nanoTime();
        buildGrid(m);
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
        if (dev.lull.optsupport.sleep.WB.TX || ctx != null && ctx.active) {
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
            pool.forRange(nGroups, 4, pool.workerCount(), g -> {
                for (int a = groupStart[g]; a < groupStart[g + 1]; a++) speculate(b0, order[a], consumer);
            });
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
            ServerReactor.pool().forRange(nGroups, 8, ServerReactor.pool().workerCount(), g -> {
                for (int a = groupStart[g + 1] - 1; a >= groupStart[g]; a--) {
                    int i = order[a];
                    if (invalid[i]) txs[i].restoreFields();
                }
            });
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
            rpool.forRange(m, 64, rpool.workerCount(), i -> {
                if (!invalid[i]) txs[i].release();
            });
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
        int[] cur = CUR.get();
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
        pool.forRange(m, 64, pool.workerCount(), i -> {   // (read-only: the grid, the boxes, the query logs)
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
        });
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
        int[] cur = CUR.get();
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
            int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & 4095;
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
                for (int y = y0; y <= y1; y++) if (!c.getListenerRegistry(y).isEmpty()) return false;
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
            pool.forRange(to - from, CHUNK, pool.workerCount(), q -> {
                int k = from + q;
                int[] cur = CUR.get();
                cur[0] = k;
                try {
                    consumer.accept(all[k]);
                } finally {
                    cur[0] = -1;
                }
            });
        } finally {
            running = false;
        }
        long t1 = System.nanoTime();
        nsPhase += t1 - t0;
        mergePaths();
        commitDeferred(from, to);
        nsCommit += System.nanoTime() - t1;
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
        return running || testing ? CUR.get()[0] : -1;
    }

    // ------------------------------------------------------------------ hooks

    public static boolean running() {
        return running;
    }

    public static boolean onWorker() {
        return running && Thread.currentThread() != main;
    }

    /** Entity.setPosRaw's levelCallback.onMove() during a run: kept for after it (a repeat of the last one dropped) */
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
    private static final ThreadLocal<Object[]> LAST_CHUNKS = ThreadLocal.withInitial(() -> new Object[8]);
    private static final ThreadLocal<long[]> LAST_POS = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, 0, -1});
    /** a new value per run: a worker's remembered chunks are good for one run only (chunks unload between ticks) */
    private static volatile long chunkEpoch;

    public static LevelChunk chunk(ServerChunkCache cache, int x, int z, boolean require) {
        long key = ChunkPos.asLong(x, z);
        long[] lp = LAST_POS.get();
        Object[] lc0 = LAST_CHUNKS.get();
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
        if (require) throw new IllegalStateException("[sleep/spec] a run tick needs chunk " + x + ", " + z + " that is not loaded");
        return null;
    }

    // ------------------------------------------------------------------ vanilla's path type cache: read-only in a run, overlays

    private static net.minecraft.world.level.pathfinder.PathTypeCache pathCache;
    private static final List<Long2ObjectOpenHashMap<PathType>> OVERLAYS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final ThreadLocal<Long2ObjectOpenHashMap<PathType>> OVERLAY = ThreadLocal.withInitial(() -> {
        Long2ObjectOpenHashMap<PathType> m = new Long2ObjectOpenHashMap<>();
        OVERLAYS.add(m);
        return m;
    });

    /** PathTypeCache.getOrCompute during a run (any thread): this thread's computes, else the shared entry, else computed */
    public static PathType pathType(long[] positions, PathType[] types, BlockGetter level, BlockPos pos) {
        if (EXACT) {
            long key = pos.asLong();
            dev.lull.optsupport.sleep.SpecTx tx = dev.lull.optsupport.sleep.SpecTx.current();
            if (tx == null || !tx.active) return dev.lull.opt.ServerParallelItemsWalkInvoker.sleep$pathTypeFromState(level, pos);   // (not a run tick)
            int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & 4095;
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
        Long2ObjectOpenHashMap<PathType> ov = OVERLAY.get();
        PathType t = ov.get(key);
        if (t != null) return t;
        int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & 4095;
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
                int idx = (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & 4095;
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
        System.out.println(String.format(java.util.Locale.ROOT,
                "[sleep/spec] walks %d: runs %d, entities in runs %d (%.0f a walk); serial walk %.3f ms (%d), parallel walk %.3f ms (%d); deferred moves %d, removes %d, effects %d; path overlay entries %d, chunk misses %d; workers %d",
                walks, runs, inRuns, walks == 0 ? 0 : inRuns / (double) walks, walksSerial == 0 ? 0 : nsSerial / 1e6 / walksSerial, walksSerial,
                walksParallel == 0 ? 0 : nsParallel / 1e6 / walksParallel, walksParallel, deferredMoves, deferredRemoves, deferredFx, pathOverlay, chunkMisses,
                ServerReactor.pool().workerCount()));
    }
}
