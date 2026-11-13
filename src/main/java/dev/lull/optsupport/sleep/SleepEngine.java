package dev.lull.optsupport.sleep;

import dev.lull.BeTickStats;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Universal sleep for block entities ({@code -Dlab.opt.SleepMixin=true}).
 *
 * <p>Per class, once: {@link SleepAnalysis} derives from bytecode the clock fields, the conditions on them and the
 * only fields a tick can change ({@code watch}); {@link SnapCompiler} compiles a GETFIELD snapshot of those fields.
 * Per block entity (state stored in the object itself, {@link SleeperHolder}), while awake, a tick counts as
 * <i>time passing</i> when: no world-visible write, no random number drawn, the same world reads as the previous
 * tick, and only clock fields changed, with the previous tick's rates. After {@value #STABLE} such ticks it sleeps
 * until {@code deadline - MARGIN} (the first tick any clock condition could change), subscribed to everything it
 * read. On wake the clocks are fast-forwarded by rate × skipped ticks and the tick runs normally; after an early wake
 * one verifying tick with the same rates and reads is enough to sleep again. Block entities that keep failing to
 * sleep are observed exponentially less often (up to every {@value #MAX_BACKOFF} ticks).
 */
public final class SleepEngine {
    public static final boolean ON = Boolean.getBoolean("lab.opt.SleepMixin");
    /** the probes feed the block-entity engine and/or the entity engine */
    public static final boolean HOOKS = ON || EntitySleep.ON;
    static final int STABLE = Integer.getInteger("lab.sleep.stable", 3);
    /** an observed-only sleeper's idle ticks before it sleeps: longer than the periods mods act on by their own timers (CoFH 10 / 20 / 40) */
    static final int OBSERVED_STABLE = Integer.getInteger("lab.sleep.observedStable", 64);
    static final int MARGIN = Integer.getInteger("lab.sleep.margin", 2);
    static final int MIN_SLEEP = Integer.getInteger("lab.sleep.min", 4);
    static final int MAX_BACKOFF = Integer.getInteger("lab.sleep.backoff", 64);
    /** A block entity must have ticked this many times before the engine tracks it. */
    static final int MIN_AGE = Integer.getInteger("lab.sleep.minAge", 40);
    /** verifyAsleep on every skipped tick (external field changes); off only for measurements */
    static final boolean VERIFY = !Boolean.getBoolean("lab.sleep.noVerify");
    /** Optional region filter for A/B-in-one-world tests: only x in [minX, maxX) may sleep. */
    static final int MIN_X = Integer.getInteger("lab.sleep.minX", Integer.MIN_VALUE), MAX_X = Integer.getInteger("lab.sleep.maxX", Integer.MAX_VALUE);

    enum State { AWAKE, ASLEEP, NEVER }

    /**
     * Value verification of sleepers' item lists: "auto" (default) for classes whose mod code bypasses the probes
     * (SleepAnalysis.probeBypassOf), "all" for every class (a second net under the probes; tests), "off": such classes
     * do not sleep.
     */
    static final String DEEP = System.getProperty("lab.sleep.deepVerify", "auto");

    /**
     * -Dlab.sleep.ObservedModSleep (on by default since 1.2.0; a setting): mods' block entities whose tick the analysis cannot
     * follow (a ticker calling the block entity through a mod's interface, contents in an item handler object) sleep
     * on what is observed while they run, as before 1.1.8 - faster idle machines, not proven exact. Read when a class
     * is analysed (a change takes a restart).
     */
    static boolean observedModSleep() {
        return Boolean.getBoolean("lab.sleep.ObservedModSleep");
    }
    static long deepChecks, deepWakes;

    /** Per class: plan + compiled snapshot. */
    static final class ClassInfo {
        final SleepAnalysis.Plan plan;
        final SnapCompiler.Snapper snapper;
        final int[] clockSlot;       // position of each plan clock in plan.watch
        final boolean[] isClock;     // per watch slot
        /** verified by value while asleep: the NonNullList fields (its item lists) of the whole class chain */
        final boolean deep;
        final Field[] lists;
        /**
         * sleeps only because of ObservedModSleep (the proof refuses it: no item list to verify, or owned state outside
         * value verification): everything it owns is hashed (OwnedHash) while observed and on every skipped tick, and a
         * neighbour change wakes it
         */
        boolean observedOnly;

        ClassInfo(SleepAnalysis.Plan plan) {
            this(plan, false, null);
        }

        ClassInfo(SleepAnalysis.Plan plan, boolean deep, Class<?> beClass) {
            this.plan = plan;
            List<Field> ls = new ArrayList<>();
            if (deep) {
                for (Class<?> c = beClass; c != null && c != Object.class; c = c.getSuperclass())
                    for (Field f : c.getDeclaredFields())
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && net.minecraft.core.NonNullList.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            ls.add(f);
                        }
            }
            this.deep = deep;
            this.lists = ls.toArray(new Field[0]);
            List<Field> w = plan.watch();
            this.snapper = plan.eligible() ? SnapCompiler.compile(w) : null;
            clockSlot = new int[plan.clocks().size()];
            isClock = new boolean[w.size()];
            for (int c = 0; c < clockSlot.length; c++) {
                clockSlot[c] = w.indexOf(plan.clocks().get(c));
                if (clockSlot[c] >= 0) isClock[clockSlot[c]] = true;
            }
        }
    }

    /** deadlines in the lab's time queue (key = Sleeper.id), polled at the start of each level's block-entity tick */
    private static final dev.lull.optsupport.LabScheduler DEADLINES = new dev.lull.optsupport.LabScheduler();
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Sleeper> SCHEDULED = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long nextId;
    static long deadlineWakes;

    static final class Sleeper {
        final long id = nextId++;
        final BlockEntity be;
        final ClassInfo info;
        State state = State.AWAKE;
        long[] prev, cur;                        // two snapshot buffers, swapped every observed tick
        Object[] refs;                           // asleep: the referenced objects of the reference slots, for verifyAsleep
        net.minecraft.world.item.ItemStack[][] deepCopy;   // asleep, a deep class: its item lists as they were
        List<Object[]> neighbours;               // asleep: read neighbours that bypass the probes {be, lists, copies}
        long[] rates, prevRates;
        boolean prevValid;
        long prevTick = Long.MIN_VALUE, prevReadHash;
        /** observed-only classes: the hash of everything the block entity owns, last observed tick / at sleep entry */
        long prevOwned, ownedAtSleep;
        /** asleep, observed-only: the block entities around it (read by its tick, or next to it) and their OwnedHash at sleep entry */
        BlockEntity[] watchedBes;
        long[] watchedOwned;
        int stable, failStreak;
        long observeFrom = Long.MIN_VALUE, noSleepUntil = Long.MIN_VALUE;
        // asleep
        long wakeAt, sleepReadHash;
        boolean woken, resleep;
        String wakeReason;
        final LongArrayList watchedPos = new LongArrayList(), watchedSections = new LongArrayList();
        final List<AABB> boxes = new ArrayList<>(2);
        // reads of the current / last observed tick (swapped, no copies)
        LongArrayList readPos = new LongArrayList(), lastReadPos = new LongArrayList();
        List<AABB> readBoxes = new ArrayList<>(2), lastReadBoxes = new ArrayList<>(2);

        Sleeper(BlockEntity be, ClassInfo info) {
            this.be = be;
            this.info = info;
            int n = info.plan.watch().size();
            prev = new long[n];
            cur = new long[n];
            refs = new Object[n];
            rates = new long[info.clockSlot.length];
            prevRates = new long[info.clockSlot.length];
        }
    }

    private static final Map<Class<?>, ClassInfo> CLASSES = new java.util.concurrent.ConcurrentHashMap<>();   // filled by AsyncAnalysis
    private static final List<Sleeper> ALL = new ArrayList<>();
    private static final Long2ObjectOpenHashMap<List<Sleeper>> BY_POS = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<List<Sleeper>> BY_SECTION = new Long2ObjectOpenHashMap<>();

    // the tick being observed (server thread)
    private static Sleeper obs;
    private static Thread obsThread;
    private static long w0, seed0, readHash;
    /** Reads of world state no subscription covers (fluids, light, weather, time of day, players, ...), counted by SleepProbeUnobservedMixin */
    static long unobserved, u0;
    static long verifiedSkips, externalWakes;

    /** Start of a level's block-entity tick: wake every sleeper whose deadline has come (stale entries skipped). */
    public static void pollDeadlines(Level level) {
        if (level.isClientSide || DEADLINES.size() == 0) return;
        long now = level.getGameTime();
        for (long id; (id = DEADLINES.takeDue(now)) != dev.lull.optsupport.LabScheduler.NONE; ) {
            Sleeper s = SCHEDULED.get(id);
            if (s == null) continue;
            if (s.state != State.ASLEEP) {
                SCHEDULED.remove(id);
            } else if (!s.woken && s.wakeAt <= now) {
                SCHEDULED.remove(id);
                deadlineWakes++;
                request(s, "deadline");
            }
        }
        DEADLINES.endTick(now);
    }

    /** From SleepProbeUnobservedMixin: the observed tick read something a sleeping block entity could not be woken by. */
    /** every unobserved read, observed tick or not (MoveMemo) */
    public static long unobservedAll;

    public static void unobservedRead() {
        unobservedAll++;
        if (EntitySleep.S().obs != null && Thread.currentThread() == EntitySleep.S().obsThread) EntitySleep.unobservedRead();
        if (obs != null && Thread.currentThread() == obsThread) unobserved++;
    }

    /** the level's weather read: a mob being observed may sleep on it while there is none (a change wakes it) */
    public static void weatherRead(net.minecraft.world.level.Level level) {
        if (EntitySleep.S().obs != null && Thread.currentThread() == EntitySleep.S().obsThread && EntitySleep.weatherRead(level)) {
            unobservedAll++;
            return;
        }
        unobservedRead();
    }

    /** the level's player list read: a mob being observed may sleep on it while it is empty (a player arriving wakes it) */
    public static void playersRead(net.minecraft.server.level.ServerLevel level, java.util.List<?> players) {
        if (EntitySleep.S().obs != null && Thread.currentThread() == EntitySleep.S().obsThread && EntitySleep.playersRead(players)) {
            unobservedAll++;
            return;
        }
        unobservedRead();
    }

    /**
     * Before every skipped tick: the watched fields must still equal the snapshot taken when it fell asleep.
     * Anything changed from outside (a bell hit, a menu, another mod calling a setter) wakes it for this very tick.
     */
    public static boolean verifyAsleep(BlockEntity be) {
        if (!VERIFY) return true;
        Sleeper s = sleeper(be);
        if (s == null || s.state != State.ASLEEP) return true;
        // only the block entity's own fields (the cache lines the skip reads anyway): primitives by value, references by
        // identity. Changes inside referenced containers arrive as events (container hooks, setChanged, block updates).
        if (s.info.snapper.same(be, s.prev, s.refs) && (!s.info.deep && s.neighbours == null || sameLists(s))
                && (!s.info.observedOnly || OwnedHash.of(be) == s.ownedAtSleep && watchedSame(s))) {
            verifiedSkips++;
            return true;
        }
        externalWakes++;
        request(s, "external change");
        return false;
    }

    private static final Map<Class<?>, String> BYPASS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> LISTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** why this block-entity class bypasses the probes (cached), or null */
    static String bypassOf(Class<?> c) {
        String r = BYPASS.computeIfAbsent(c, k -> {
            if (TEST_BYPASS.contains(k.getSimpleName())) return "test bypass";
            String b = SleepAnalysis.probeBypassOf(k);
            return b == null ? "" : b;
        });
        return r.isEmpty() ? null : r;
    }

    /** the NonNullList fields (item lists) of a block-entity class and its superclasses */
    /** types whose instances never change: a field of such a type is state the snapshot of the field itself covers */
    private static boolean immutableType(Class<?> t) {
        if (t.isPrimitive() || t.isEnum() || t == String.class || Number.class.isAssignableFrom(t) && t.getName().startsWith("java.lang.")
                || t == Boolean.class || t == Character.class || t == java.util.UUID.class || t == Class.class) return true;
        String n = t.getName();
        return n.equals("net.minecraft.core.BlockPos") || n.equals("net.minecraft.world.level.block.state.BlockState") || n.equals("net.minecraft.core.Direction")
                || n.equals("net.minecraft.resources.ResourceLocation") || n.equals("net.minecraft.resources.ResourceKey") || n.equals("net.minecraft.core.Holder")
                || n.equals("net.minecraft.network.chat.Component") || n.equals("net.minecraft.world.level.block.entity.BlockEntityType")
                || n.equals("net.minecraft.world.level.Level") || n.equals("net.minecraft.server.level.ServerLevel") || n.equals("net.minecraft.world.phys.AABB")
                || n.equals("net.minecraft.world.phys.Vec3");
    }

    /** the first field of the class chain (below BlockEntity) holding a changeable object other than an item list; null when none */
    static String unverifiedState(Class<?> beClass) {
        for (Class<?> c = beClass; c != null && c != Object.class && !c.getName().equals("net.minecraft.world.level.block.entity.BlockEntity"); c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (immutableType(t) || net.minecraft.core.NonNullList.class.isAssignableFrom(t)) continue;
                return c.getSimpleName() + "." + f.getName() + " (" + t.getSimpleName() + ")";
            }
        return null;
    }

    static Field[] listsOf(Class<?> beClass) {
        return LISTS.computeIfAbsent(beClass, k -> {
            List<Field> ls = new ArrayList<>();
            for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields())
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && net.minecraft.core.NonNullList.class.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            ls.add(f);
                        } catch (RuntimeException e) {
                            // an inaccessible list: the class is checked without it (its probes still count)
                        }
                    }
            return ls.toArray(new Field[0]);
        });
    }

    /** the block entity at a position, straight from its loaded chunk (no creation, no probe), or null */
    private static BlockEntity beAt(Level level, long pos) {
        BlockPos p = BlockPos.of(pos);
        net.minecraft.world.level.chunk.LevelChunk c = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        return c == null ? null : c.getBlockEntities().get(p);
    }

    /** copies of a block entity's item lists */
    private static net.minecraft.world.item.ItemStack[][] copyOf(Object be, Field[] lists) {
        net.minecraft.world.item.ItemStack[][] out = new net.minecraft.world.item.ItemStack[lists.length][];
        try {
            for (int i = 0; i < out.length; i++) {
                List<?> l = (List<?>) lists[i].get(be);
                if (l == null) continue;
                net.minecraft.world.item.ItemStack[] c = new net.minecraft.world.item.ItemStack[l.size()];
                for (int j = 0; j < c.length; j++)
                    c[j] = l.get(j) instanceof net.minecraft.world.item.ItemStack st ? st.copy() : net.minecraft.world.item.ItemStack.EMPTY;
                out[i] = c;
            }
        } catch (ReflectiveOperationException e) {
            return null;
        }
        return out;
    }

    /** a block entity's item lists against copies (item, count, components) */
    private static boolean sameAs(Object be, Field[] lists, net.minecraft.world.item.ItemStack[][] copy) {
        if (copy == null) return true;
        try {
            for (int i = 0; i < lists.length; i++) {
                List<?> l = (List<?>) lists[i].get(be);
                net.minecraft.world.item.ItemStack[] c = copy[i];
                if (l == null ? c != null : c == null || l.size() != c.length) return false;
                if (l == null) continue;
                for (int j = 0; j < c.length; j++)
                    if (!(l.get(j) instanceof net.minecraft.world.item.ItemStack st) || !net.minecraft.world.item.ItemStack.matches(st, c[j])) return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** a deep class' item lists against their copies from when it fell asleep (item, count, components) */
    private static boolean sameLists(Sleeper s) {
        deepChecks++;
        if (s.info.deep && !sameAs(s.be, s.info.lists, s.deepCopy)) {
            deepWakes++;
            return false;
        }
        List<Object[]> nb = s.neighbours;
        if (nb != null)
            for (int i = 0; i < nb.size(); i++) {
                Object[] n = nb.get(i);
                if (!sameAs(n[0], (Field[]) n[1], (net.minecraft.world.item.ItemStack[][]) n[2])) {
                    deepWakes++;
                    return false;
                }
            }
        return true;
    }

    /** copies of a deep class' item lists (at sleep entry) */
    private static net.minecraft.world.item.ItemStack[][] copyLists(Sleeper s) {
        return copyOf(s.be, s.info.lists);
    }

    /**
     * At sleep entry: the read neighbours whose class bypasses the probes - their writes reach no probe, so a change of
     * their item lists is found by value (a vanilla hopper under PneumaticCraft's chest)
     */
    /** an observed-only sleeper watches at most this many block entities around it (more: it does not sleep for a while) */
    private static final int MAX_WATCHED = Integer.getInteger("lab.sleep.observedWatchMax", 8);
    public static long watchWakes, watchRefusals;

    /**
     * ObservedModSleep: the block entities an observed-only sleeper could send to or take from - those its last observed
     * tick read and the six next to it - hashed at sleep entry (verifyAsleep compares them). A transmitter idle because
     * its target is full wakes when the target drains.
     */
    private static void watchAround(Sleeper s, Level level) {
        s.watchedBes = null;
        s.watchedOwned = null;
        BlockPos own = s.be.getBlockPos();
        java.util.LinkedHashSet<BlockEntity> found = new java.util.LinkedHashSet<>();
        LongArrayList rp = s.lastReadPos;
        for (int i = 0; i < rp.size(); i++) {
            long p = rp.getLong(i);
            if (p == own.asLong()) continue;
            BlockEntity n = beAt(level, p);
            if (n != null) found.add(n);
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockEntity n = beAt(level, own.relative(d).asLong());
            if (n != null) found.add(n);
        }
        if (found.isEmpty()) return;
        if (found.size() > MAX_WATCHED) {   // too many to hash on every skipped tick: awake for a minute
            watchRefusals++;
            s.noSleepUntil = level.getGameTime() + 1200;
            request(s, "too many block entities around to watch");
            return;
        }
        s.watchedBes = found.toArray(new BlockEntity[0]);
        s.watchedOwned = new long[s.watchedBes.length];
        for (int i = 0; i < s.watchedBes.length; i++) s.watchedOwned[i] = OwnedHash.of(s.watchedBes[i]);
    }

    /** the watched block entities as they were at sleep entry (none removed, none changed) */
    private static boolean watchedSame(Sleeper s) {
        BlockEntity[] w = s.watchedBes;
        if (w == null) return true;
        for (int i = 0; i < w.length; i++) {
            if (w[i].isRemoved() || OwnedHash.of(w[i]) != s.watchedOwned[i]) {
                watchWakes++;
                return false;
            }
        }
        return true;
    }

    private static void watchNeighbours(Sleeper s) {
        s.neighbours = null;
        Level level = s.be.getLevel();
        if (level == null) return;
        if (s.info.observedOnly) watchAround(s, level);
        long own = s.be.getBlockPos().asLong();
        LongArrayList rp = s.lastReadPos;
        for (int i = 0; i < rp.size(); i++) {
            long p = rp.getLong(i);
            if (p == own) continue;
            BlockEntity n = beAt(level, p);
            if (n == null || bypassOf(n.getClass()) == null) continue;
            Field[] lists = listsOf(n.getClass());
            if (lists.length == 0) continue;
            if (s.neighbours == null) s.neighbours = new ArrayList<>(2);
            s.neighbours.add(new Object[] {n, lists, copyOf(n, lists)});
        }
    }

    // stats
    static long skipped, entered, resleeps, observedTicks, backedOff, materialized;
    /** ticks skipped by the fast path at the head of BoundTickingBlockEntity.tick (SleepProbeFastSkipMixin) */
    public static long fastSkipped;
    static final Map<String, Long> WAKES = new TreeMap<>();
    static final Map<String, Long> REFUSED = new TreeMap<>();

    private SleepEngine() {}

    private static Sleeper sleeper(BlockEntity be) {
        return (Sleeper) ((SleeperHolder) be).sleep$sleeper();
    }

    private static SleeperHolder h(Sleeper s) {
        return (SleeperHolder) s.be;
    }

    // ------------------------------------------------------------------ tick hooks

    /** At the ticker call site (reached only when vanilla really ticks): true = skip this tick. */
    public static boolean skip(BlockEntity be) {
        Sleeper s = sleeper(be);
        if (s == null || s.state != State.ASLEEP) return false;
        Level level = be.getLevel();
        long now = level.getGameTime();
        if (!s.woken && now < s.wakeAt && verifyAsleep(be)) {
            skipped++;
            h(s).sleep$setSkipped(h(s).sleep$skipped() + 1);
            return true;
        }
        wake(s, now, s.woken ? s.wakeReason : "deadline");
        return false;
    }

    private static void wake(Sleeper s, long now, String reason) {
        advance(s);
        unsubscribe(s);
        s.state = State.AWAKE;
        s.woken = false;
        h(s).sleep$setWakeAt(Long.MIN_VALUE);
        java.util.Arrays.fill(s.refs, null);   // do not keep replaced objects alive
        s.deepCopy = null;
        s.neighbours = null;
        // the snapshot taken now is the "before" of this tick: one verifying tick may put it back to sleep
        s.info.snapper.snap(s.be, s.prev);
        s.prevValid = true;
        s.prevTick = now - 1;
        s.prevReadHash = s.sleepReadHash;
        System.arraycopy(s.rates, 0, s.prevRates, 0, s.rates.length);
        s.resleep = true;
        s.stable = 0;
        s.observeFrom = Long.MIN_VALUE;
        WAKES.merge(reason, 1L, Long::sum);
        if (SleepCoverage.ON) SleepCoverage.woke(s.be);
    }

    /** Apply the pending time to the clock fields (rate x skipped ticks); the block entity may keep sleeping. */
    private static void advance(Sleeper s) {
        long k = h(s).sleep$skipped();
        h(s).sleep$setSkipped(0);
        if (k <= 0) return;
        if (SleepCoverage.ON) SleepCoverage.skipped(s.be, k);
        List<Field> clocks = s.info.plan.clocks();
        for (int c = 0; c < clocks.size(); c++) {
            long r = s.rates[c];
            if (r == 0) continue;
            Field f = clocks.get(c);
            try {
                if (f.getType() == int.class) f.setInt(s.be, (int) (f.getInt(s.be) + r * k));
                else if (f.getType() == long.class) f.setLong(s.be, f.getLong(s.be) + r * k);
                else if (f.getType() == short.class) f.setShort(s.be, (short) (f.getShort(s.be) + r * k));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Before a normal tick: start observing (reads, writes, random), unless backed off. */
    public static void before(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;
        Sleeper s = sleeper(be);
        if (s == null) {
            // short-lived block entities (moving pistons: thousands, a few ticks each) never get a Sleeper
            SleeperHolder h = (SleeperHolder) be;
            int age = h.sleep$age() + 1;
            h.sleep$setAge(age);
            if (age < MIN_AGE) return;
            s = create(be);
            if (s == null) return;   // analysed in the background: vanilla's tick meanwhile
        }
        if (s.state == State.NEVER) return;
        if (level.getGameTime() < s.observeFrom) {
            s.prevValid = false;   // not observed: the next observation starts a fresh sequence
            return;
        }
        obs = s;
        obsThread = Thread.currentThread();
        w0 = BeTickStats.writes;
        seed0 = seed(level);
        u0 = unobserved;
        readHash = 17;
        s.readPos.clear();
        s.readBoxes.clear();
    }

    private static Sleeper create(BlockEntity be) {
        Class<?> blockClass = be.getBlockState().getBlock().getClass();
        boolean excluded = dev.lull.api.Compat.sleepExcluded(be);
        boolean watchedOnly = dev.lull.api.Compat.sleepObservedOnly(be);   // the pack or the mod asked: watched sleep only
        String name = be.getClass().getSimpleName();
        ClassInfo info = AsyncAnalysis.get(CLASSES, be.getClass(), c -> {
            @SuppressWarnings("unchecked") Class<? extends net.minecraft.world.level.block.entity.BlockEntity> bc = (Class<? extends net.minecraft.world.level.block.entity.BlockEntity>) c;
            SleepAnalysis.Plan p = SleepAnalysis.analyze(bc, blockClass);
            if (p.eligible() && (p.clocks().stream().anyMatch(java.util.Objects::isNull) || p.conds().stream().anyMatch(x -> x.mod() == 0 && x.clock() == null)))
                p = new SleepAnalysis.Plan(false, "inaccessible clock", p.clocks(), p.conds());
            if (p.eligible() && excluded)
                p = new SleepAnalysis.Plan(false, "excluded through dev.lull.api.Compat", p.clocks(), p.conds());
            String bypass = p.eligible() ? bypassOf(c) : null;
            if (bypass != null && "off".equals(DEEP)) p = new SleepAnalysis.Plan(false, bypass, p.clocks(), p.conds());
            // its contents live where value verification cannot see them (a mod's item handler object): no proof, no sleep
            if (bypass != null && p.eligible() && listsOf(c).length == 0 && !observedModSleep())
                p = new SleepAnalysis.Plan(false, bypass + "; no item list to verify it by value", p.clocks(), p.conds());
            // and nothing it owns may change unseen: with the probes blind, value verification covers its item lists only - another
            // object it holds (an energy storage, a tank, a handler) filled through a capability while it sleeps would not wake
            // it (Immersive Engineering's turret: energy 5 760 awake, 16 000 asleep - 11-09)
            String unseen = bypass != null && p.eligible() && !observedModSleep() ? unverifiedState(c) : null;
            if (unseen != null) p = new SleepAnalysis.Plan(false, bypass + "; owned state outside value verification: " + unseen, p.clocks(), p.conds());
            boolean deep = p.eligible() && ("all".equals(DEEP) || bypass != null);
            ClassInfo ci = new ClassInfo(p, deep, c);
            ci.observedOnly = p.eligible() && observedModSleep()
                    && (bypass != null && (listsOf(c).length == 0 || unverifiedState(c) != null) || SleepAnalysis.needsObservation(c))
                    || p.eligible() && watchedOnly;
            System.out.println("[sleep/sleep] " + name + ": " + (p.eligible() ? "ELIGIBLE " : "no: ") + p.reason()
                    + (deep ? " [verified by value, " + ci.lists.length + " item list(s)" + (bypass != null ? ": " + bypass : "") + "]" : "")
                    + "  clocks=" + p.clocks().stream().map(f -> f == null ? "?" : f.getName()).toList()
                    + " conds=" + p.conds().stream().map(SleepAnalysis.Cond::text).toList()
                    + (p.eligible() ? " watch=" + p.watch().stream().map(Field::getName).toList() + (SnapCompiler.failure != null ? " SNAP FALLBACK: " + SnapCompiler.failure : " (compiled snapshot)") : ""));
            return ci;
        });
        if (info == null) return null;   // its class is being analysed in the background
        Sleeper s = new Sleeper(be, info);
        int x = be.getBlockPos().getX();
        if (!info.plan.eligible() || x < MIN_X || x >= MAX_X) s.state = State.NEVER;
        dev.lull.optsupport.ModReport.blockEntity(be.getType(), excluded ? "off (a rule)" : !info.plan.eligible() ? "never (" + dev.lull.optsupport.ModReport.kind(info.plan.reason()) + "): " + info.plan.reason()
                : info.observedOnly ? "watched (sleeps after " + OBSERVED_STABLE + " idle ticks)" : "sleeps");
        ((SleeperHolder) be).sleep$setSleeper(s);
        ALL.add(s);
        if ((ALL.size() & 4095) == 0) ALL.removeIf(o -> o.be.isRemoved() && o.state != State.ASLEEP);   // bounded bookkeeping
        return s;
    }

    /** After a normal tick: classify it and maybe fall asleep. */
    public static void after(BlockEntity be) {
        Sleeper s = obs;
        if (s == null || s.be != be) return;
        obs = null;
        obsThread = null;
        observedTicks++;
        Level level = be.getLevel();
        long now = level.getGameTime();
        long[] cur = s.cur;
        s.info.snapper.snap(be, cur);
        boolean clockOnly = s.prevValid && s.prevTick == now - 1 && BeTickStats.writes == w0 && seed(level) == seed0
                && readHash == s.prevReadHash && unobserved == u0;
        if (s.info.observedOnly) {   // what it owns (an energy storage spent every tick, a tank, a timer object) counts too
            long owned = OwnedHash.of(be);
            if (owned != s.prevOwned) clockOnly = false;
            s.prevOwned = owned;
        }
        if (clockOnly) {
            long[] prev = s.prev;
            boolean[] isClock = s.info.isClock;
            for (int i = 0; i < cur.length; i++) {
                if (cur[i] != prev[i] && !isClock[i]) { clockOnly = false; break; }
            }
        }
        boolean sameRates = false;
        if (clockOnly) {
            sameRates = true;
            int[] slots = s.info.clockSlot;
            for (int c = 0; c < slots.length; c++) {
                long r = cur[slots[c]] - s.prev[slots[c]];
                if (r != s.prevRates[c]) sameRates = false;
                s.rates[c] = r;
            }
            s.stable = sameRates ? s.stable + 1 : 1;
        } else {
            s.stable = 0;
        }
        // swap snapshot buffers and read lists: no copies, no allocation
        s.cur = s.prev;
        s.prev = cur;
        s.prevValid = true;
        s.prevTick = now;
        s.prevReadHash = readHash;
        LongArrayList rp = s.lastReadPos;
        s.lastReadPos = s.readPos;
        s.readPos = rp;
        List<AABB> rb = s.lastReadBoxes;
        s.lastReadBoxes = s.readBoxes;
        s.readBoxes = rb;
        System.arraycopy(s.rates, 0, s.prevRates, 0, s.rates.length);

        boolean resleep = s.resleep;
        s.resleep = false;
        // an observed-only sleeper: its tick was not followed - it may act on a mod's own timer (CoFH's dynamos start on a
        // global counter every 10 ticks, nothing of theirs changing): idle for OBSERVED_STABLE ticks first, no quick resleep
        int need = s.info.observedOnly ? OBSERVED_STABLE : STABLE;
        if ((resleep && clockOnly && sameRates && !s.info.observedOnly) || s.stable >= need) {
            if (resleep && clockOnly && sameRates) resleeps++;
            trySleep(s, now);
        } else if (!clockOnly) {
            fail(s, now, 16);   // busy: back off after a streak of busy ticks
        }
    }

    private static void fail(Sleeper s, long now, int threshold) {
        if (++s.failStreak < threshold) return;
        int shift = Math.min(s.failStreak - threshold, 6);
        long wait = Math.min(MAX_BACKOFF, 2L << shift);
        s.observeFrom = now + wait;
        s.prevValid = false;
        backedOff++;
    }

    private static void trySleep(Sleeper s, long now) {
        if (now < s.noSleepUntil) {
            refuse("viewed");
            return;
        }
        long t = Long.MAX_VALUE;
        try {
            for (SleepAnalysis.Cond cond : s.info.plan.conds()) t = Math.min(t, ticksUntilChange(s, cond, now));
        } catch (IllegalAccessException e) {
            refuse("reflection");
            return;
        }
        long sleep = t == Long.MAX_VALUE ? Long.MAX_VALUE : t - MARGIN;
        if (sleep < MIN_SLEEP) {
            refuse(t == 0 ? "condition changes now" : "deadline too close");
            fail(s, now, 1);
            return;
        }
        s.failStreak = 0;
        h(s).sleep$setSkipped(0);
        s.wakeAt = sleep == Long.MAX_VALUE ? Long.MAX_VALUE : now + sleep;
        // the deadline lives in the time queue; the fast skip only checks the woken flag (wakeAt MIN_VALUE)
        if (s.wakeAt != Long.MAX_VALUE) {
            DEADLINES.add(s.wakeAt, s.id);
            SCHEDULED.put(s.id, s);
        }
        h(s).sleep$setWakeAt(Long.MAX_VALUE);
        s.sleepReadHash = s.prevReadHash;
        s.state = State.ASLEEP;
        s.woken = false;
        s.info.snapper.refs(s.be, s.refs);   // reference for verifyAsleep (s.prev holds the primitive slots)
        if (s.info.observedOnly) s.ownedAtSleep = s.prevOwned;
        if (s.info.deep) s.deepCopy = copyLists(s);
        watchNeighbours(s);
        subscribe(s);
        entered++;
        if (SleepCoverage.ON) SleepCoverage.entered(s.be);
    }

    /** Ticks until the relation between clock and threshold (or the game time residue) can change. */
    private static long ticksUntilChange(Sleeper s, SleepAnalysis.Cond c, long now) throws IllegalAccessException {
        if (c.clockMod()) return rateOf(s, c.clock()) == 0 ? Long.MAX_VALUE : 1;   // periodic on a moving clock: may change every tick
        if (c.mod() > 0) return Math.floorMod(c.thresholdConst() - (now + 1), c.mod()) + 1;
        long v = value(c.clock(), s.be), rv = rateOf(s, c.clock());
        long h = (c.thresholdField() != null ? value(c.thresholdField(), s.be) : 0) + c.thresholdConst();   // field + offset, or constant
        long rh = c.thresholdField() != null ? rateOf(s, c.thresholdField()) : 0;
        long d = v - h, rd = rv - rh;
        if (rd == 0) return Long.MAX_VALUE;
        if (d == 0) return 0;
        if (Long.signum(d) == Long.signum(rd)) return Long.MAX_VALUE;
        return (Math.abs(d) + Math.abs(rd) - 1) / Math.abs(rd);
    }

    private static long rateOf(Sleeper s, Field f) {
        List<Field> clocks = s.info.plan.clocks();
        for (int c = 0; c < clocks.size(); c++) if (clocks.get(c).equals(f)) return s.rates[c];
        return 0;
    }

    private static long value(Field f, Object o) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) return f.getInt(o);
        if (t == long.class) return f.getLong(o);
        if (t == short.class) return f.getShort(o);
        if (t == byte.class) return f.getByte(o);
        throw new IllegalAccessException("non-integral clock " + f);
    }

    private static void refuse(String why) {
        REFUSED.merge(why, 1L, Long::sum);
    }

    // ------------------------------------------------------------------ external observation

    /** A save (or any exact read): bring the lazily advanced clocks up to date; the block entity keeps sleeping. */
    public static void materialize(BlockEntity be) {
        Sleeper s = sleeper(be);
        if (s != null && s.state == State.ASLEEP && h(s).sleep$skipped() > 0) {
            advance(s);
            s.info.snapper.snap(s.be, s.prev);   // the clocks moved on: this is the new reference for verifyAsleep
            materialized++;
        }
    }

    /** Fields were replaced from NBT: drop the pending time and re-observe. */
    public static void onLoad(BlockEntity be) {
        Sleeper s = sleeper(be);
        if (s == null) return;
        if (s.state == State.ASLEEP) {
            h(s).sleep$setSkipped(0);
            request(s, "load");
        }
        s.stable = 0;
        s.prevValid = false;
    }

    /** An open menu reads the fields every tick: keep the block entity awake while it is watched. */
    public static void viewed(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide) return;
        Sleeper s = sleeper(be);
        if (s == null) return;
        s.noSleepUntil = level.getGameTime() + 20;
        if (s.state == State.ASLEEP) {
            advance(s);
            request(s, "viewed");
        }
    }

    // ------------------------------------------------------------------ reads (from ReadProbeMixin)

    public static void readPos(char kind, BlockPos pos) {
        if (EntitySleep.S().obs != null && Thread.currentThread() == EntitySleep.S().obsThread) EntitySleep.readPos(kind, pos.asLong());
        Sleeper s = obs;
        if (s == null || Thread.currentThread() != obsThread) return;
        long p = pos.asLong();
        s.readPos.add(p);
        readHash = readHash * 1_000_003L + kind * 31L + p;
    }

    /** Block collision / support queries over a box (from the collision memo overrides). */
    public static void readRegion(AABB box) {
        if (EntitySleep.S().obs != null && Thread.currentThread() == EntitySleep.S().obsThread) EntitySleep.readRegion(box);
    }

    public static void readBox(char kind, AABB box) {
        if (EntitySleep.S().obs != null && Thread.currentThread() == EntitySleep.S().obsThread) EntitySleep.readBox(kind, box);
        Sleeper s = obs;
        if (s == null || Thread.currentThread() != obsThread) return;
        s.readBoxes.add(box);
        readHash = readHash * 1_000_003L + kind * 31L + Double.doubleToLongBits(box.minX) * 7 + Double.doubleToLongBits(box.minY) * 13
                + Double.doubleToLongBits(box.minZ) * 17 + Double.doubleToLongBits(box.maxX + box.maxY + box.maxZ);
    }

    // ------------------------------------------------------------------ subscriptions and wake events

    private static void subscribe(Sleeper s) {
        watchPos(s, s.be.getBlockPos().asLong());
        LongArrayList rp = s.lastReadPos;
        for (int i = 0; i < rp.size(); i++) watchPos(s, rp.getLong(i));
        for (AABB b : s.lastReadBoxes) {
            s.boxes.add(b);
            int x0 = SectionPos.posToSectionCoord(b.minX), x1 = SectionPos.posToSectionCoord(b.maxX);
            int y0 = SectionPos.posToSectionCoord(b.minY), y1 = SectionPos.posToSectionCoord(b.maxY);
            int z0 = SectionPos.posToSectionCoord(b.minZ), z1 = SectionPos.posToSectionCoord(b.maxZ);
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                long key = SectionPos.asLong(x, y, z);
                if (!s.watchedSections.contains(key)) {
                    s.watchedSections.add(key);
                    BY_SECTION.computeIfAbsent(key, k -> new ArrayList<>(2)).add(s);
                }
            }
        }
    }

    private static void watchPos(Sleeper s, long p) {
        if (s.watchedPos.contains(p)) return;
        s.watchedPos.add(p);
        BY_POS.computeIfAbsent(p, k -> new ArrayList<>(2)).add(s);
    }

    private static void unsubscribe(Sleeper s) {
        for (int i = 0; i < s.watchedPos.size(); i++) {
            long p = s.watchedPos.getLong(i);
            List<Sleeper> l = BY_POS.get(p);
            if (l != null && l.remove(s) && l.isEmpty()) BY_POS.remove(p);
        }
        for (int i = 0; i < s.watchedSections.size(); i++) {
            long k = s.watchedSections.getLong(i);
            List<Sleeper> l = BY_SECTION.get(k);
            if (l != null && l.remove(s) && l.isEmpty()) BY_SECTION.remove(k);
        }
        s.watchedPos.clear();
        s.watchedSections.clear();
        s.boxes.clear();
    }

    /** ObservedModSleep (NeighbourWakeMixin): a neighbour of this position changed - an observed-only sleeper there wakes. */
    public static void neighbourAt(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || !observedModSleep()) return;
        BlockEntity be = beAt(level, pos.asLong());
        if (be == null) return;
        Sleeper s = sleeper(be);
        if (s != null && s.info.observedOnly && s.state == State.ASLEEP) request(s, "neighbour changed");
    }

    private static void request(Sleeper s, String reason) {
        if (s.state == State.ASLEEP && !s.woken) {
            s.woken = true;
            s.wakeReason = reason;
            h(s).sleep$setWakeAt(Long.MIN_VALUE);   // the fast skip lets it through; skip() then wakes it
        }
    }

    /** A block changed, a block entity was marked changed, or a container slot was set at this position. */
    /**
     * Test only (-Dsleep.test.bypass=FurnaceBlockEntity,...): these block-entity classes are treated as bypassing the
     * probes - their container / setChanged wake-ups are dropped and the analysis reports them - a mod like
     * PneumaticCraft simulated on vanilla's scenes, so exactness shows whether value verification covers it
     */
    static final java.util.Set<String> TEST_BYPASS = java.util.Set.of(System.getProperty("sleep.test.bypass", "").split(","));

    /** Test only (-Dsleep.test.dropWakes=changed,container): these wake reasons are dropped for every class - the
     *  negative control of tools/mod_exactness.py (a scene that cannot tell this from the real engine proves nothing) */
    static final java.util.Set<String> TEST_DROP = java.util.Set.of(System.getProperty("sleep.test.dropWakes", "").split(","));

    public static void onPos(Level level, BlockPos pos, String reason) {
        if (TEST_DROP.contains(reason)) return;
        if (TEST_BYPASS.size() > 1 || !TEST_BYPASS.contains("")) {
            if (("container".equals(reason) || "changed".equals(reason)) && level != null) {
                BlockEntity t = beAt(level, pos.asLong());
                if (t != null && TEST_BYPASS.contains(t.getClass().getSimpleName())) return;
            }
        }
        if (EntitySleep.ON && level != null && (!level.isClientSide || EntitySleep.CLIENT_ON)) EntitySleep.onPos(level, pos);
        if (BY_POS.isEmpty() || level == null || level.isClientSide) return;
        List<Sleeper> l = BY_POS.get(pos.asLong());
        if (l == null) return;
        for (int i = 0; i < l.size(); i++) {
            Sleeper s = l.get(i);
            if (s.be.getLevel() == level) request(s, reason);
        }
    }

    /** An entity moved, appeared, changed its item or was removed: wake sleepers whose read boxes it touches. */
    public static void onEntity(Entity e, String reason) {
        AABB bb = e.getBoundingBox();
        onEntity(e, reason, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    /**
     * An entity's bounding box changes from {@code from} to {@code to} (a move): one event over both, so a sleeper hears
     * an entity leaving its box and one arriving (vanilla sets the new box after the position; a test of the box at the
     * position change saw the old one, and a boat stopping in a sleeping item's box never woke it).
     */
    public static void onMove(Entity e, AABB from, AABB to) {
        if (from.equals(to)) return;   // the same box set again (a position packet with the place it had): nothing any box query sees moved
        double ax = Math.min(from.minX, to.minX), ay = Math.min(from.minY, to.minY), az = Math.min(from.minZ, to.minZ),
                bx = Math.max(from.maxX, to.maxX), by = Math.max(from.maxY, to.maxY), bz = Math.max(from.maxZ, to.maxZ);
        // A move changes which entities are in the boxes that meet the old box or the new one, nothing else: both boxes
        // together are a step. A jump (a new entity placed from the constructor's (0, 0, 0), a teleport, a portal) made
        // one box from origin to destination, every section in it looked up: 384M lookups per entity loaded 64 000
        // blocks out - world generation there ran at half vanilla's speed (11-09). Then each box on its own; their hull
        // would only wake more sleepers, never fewer.
        if (sections(ax, ay, az, bx, by, bz) > sections(from.minX, from.minY, from.minZ, from.maxX, from.maxY, from.maxZ)
                + sections(to.minX, to.minY, to.minZ, to.maxX, to.maxY, to.maxZ)) {
            onEntity(e, "entity-moved", from.minX, from.minY, from.minZ, from.maxX, from.maxY, from.maxZ);
            onEntity(e, "entity-moved", to.minX, to.minY, to.minZ, to.maxX, to.maxY, to.maxZ);
            return;
        }
        onEntity(e, "entity-moved", ax, ay, az, bx, by, bz);
    }

    /** the number of sections a box spans (as a long: a hull can be astronomically large) */
    private static long sections(double ax, double ay, double az, double bx, double by, double bz) {
        return ((long) SectionPos.posToSectionCoord(bx) - SectionPos.posToSectionCoord(ax) + 1)
                * ((long) SectionPos.posToSectionCoord(by) - SectionPos.posToSectionCoord(ay) + 1)
                * ((long) SectionPos.posToSectionCoord(bz) - SectionPos.posToSectionCoord(az) + 1);
    }

    static void onEntity(Entity e, String reason, double ax, double ay, double az, double bx, double by, double bz) {
        if (EntitySleep.ON) EntitySleep.onEntity(e, reason, ax, ay, az, bx, by, bz);
        if (BY_SECTION.isEmpty()) return;
        Level level = e.level();
        if (level == null || level.isClientSide) return;
        int x0 = SectionPos.posToSectionCoord(ax), x1 = SectionPos.posToSectionCoord(bx);
        int y0 = SectionPos.posToSectionCoord(ay), y1 = SectionPos.posToSectionCoord(by);
        int z0 = SectionPos.posToSectionCoord(az), z1 = SectionPos.posToSectionCoord(bz);
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            List<Sleeper> l = BY_SECTION.get(SectionPos.asLong(x, y, z));
            if (l == null) continue;
            for (int i = 0; i < l.size(); i++) {
                Sleeper s = l.get(i);
                if (s.be.getLevel() != level) continue;
                for (AABB b : s.boxes) if (b.intersects(ax, ay, az, bx, by, bz)) { request(s, reason); break; }
            }
        }
    }

    // ------------------------------------------------------------------ random

    private static Field seedField;
    private static boolean seedTried;
    private static long unreadableSeed;

    static long seed(Level level) {
        if (!seedTried) {
            seedTried = true;
            // the class chain: a mod's random may extend vanilla's (C2ME's CheckedThreadLocalRandom extends SingleThreadedRandomSource)
            for (Class<?> c = level.random.getClass(); c != null && seedField == null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("seed");
                    f.setAccessible(true);
                    seedField = f;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // the superclass next
                }
            }
            if (seedField == null)
                System.out.println("[sleep/sleep] level.random seed not readable (" + level.random.getClass().getName() + "): every tick counts as using the shared random");
        }
        if (seedField == null) return ++unreadableSeed;   // never the same twice: no tick is taken for quiet
        try {
            Object v = seedField.get(level.random);
            return v instanceof AtomicLong a ? a.get() : v instanceof Long l ? l : 0;
        } catch (IllegalAccessException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ report

    /** Static entity-mode analysis of every entity type's class (one instance created per type, then discarded). */
    public static String entityReport(net.minecraft.server.level.ServerLevel level) {
        Map<String, String> out = new TreeMap<>();
        Map<Class<?>, String> done = new java.util.HashMap<>();
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            net.minecraft.world.entity.Entity e;
            try {
                e = type.create(level);
            } catch (RuntimeException ex) {
                continue;
            }
            if (e == null) continue;
            Class<?> c = e.getClass();
            String r = done.computeIfAbsent(c, k -> {
                SleepAnalysis.Plan p = SleepAnalysis.analyzeEntity(k);
                return (p.eligible() ? "ELIGIBLE " : "no: ") + p.reason() + " clocks=" + p.clocks().stream().map(f -> f == null ? "?" : f.getName()).toList() + " conds=" + p.conds().stream().map(SleepAnalysis.Cond::text).toList();
            });
            out.put(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), r);
        }
        StringBuilder sb = new StringBuilder("entity sleep analysis:" + System.lineSeparator());
        out.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  %-36s %s%n", k, v)));
        return sb.toString();
    }

    /** the settings screen's overview (server thread, once a second): {tracked, asleep now, ticks skipped so far} */
    public static long[] snapshot() {
        int asleep = 0;
        for (Sleeper s : ALL) if (s.state == State.ASLEEP) asleep++;
        return new long[] {ALL.size(), asleep, skipped + fastSkipped};
    }

    public static String report() {
        Map<String, int[]> byType = new TreeMap<>();
        int asleep = 0;
        for (Sleeper s : ALL) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(s.be.getType());
            int[] v = byType.computeIfAbsent(id == null ? "?" : id.toString(), k -> new int[3]);
            v[0]++;
            if (s.state == State.ASLEEP) { v[1]++; asleep++; }
            if (s.state == State.NEVER) v[2]++;
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "sleep engine: %s; tracked %d, asleep now %d; skipped ticks %d (fast path %d), sleeps entered %d (re-sleeps %d), observed ticks %d, backed off %d, materialized %d; verified skips %d, external-change wakes %d, unobserved reads %d%n",
                ON ? "ON" : "OFF", ALL.size(), asleep, skipped + fastSkipped, fastSkipped, entered, resleeps, observedTicks, backedOff, materialized,
                verifiedSkips, externalWakes, unobserved));
        sb.append(String.format(Locale.ROOT, "  deadline queue: %d pending, %d added, %d woken by the scheduler%n", DEADLINES.size(), DEADLINES.added, deadlineWakes));
        byType.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  %-34s tracked %6d  asleep %6d  never %6d%n", k, v[0], v[1], v[2])));
        sb.append("  wakes: ").append(WAKES).append(System.lineSeparator());
        sb.append("  refused: ").append(REFUSED).append(System.lineSeparator());
        ALL.removeIf(s -> s.be.isRemoved() && s.state != State.ASLEEP);
        return sb.toString();
    }
}
