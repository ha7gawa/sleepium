package dev.lull.optsupport.sleep;

import dev.lull.BeTickStats;
import dev.lull.optsupport.LabScheduler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Entity sleep ({@code -Dlab.opt.EntitySleep=true}): period replay of idle entities, vanilla and modded alike.
 *
 * <p>The class's tick() is analysed once (SleepAnalysis, entity mode): its own state, its clocks (tickCount and
 * counters), the conditions on them and the period L of every "clock % N" test. At run time an entity is observed:
 * a full snapshot of all its fields (vectors, boxes, synched data, random seed by value) before and after each tick.
 * When L consecutive ticks made no world-visible write, drew no shared random number, read nothing unobserved, and
 * the state before tick L equals the state before tick 0 (clocks aside), the tick is a periodic function of state the
 * engine holds: the same inputs give the same L ticks again. The entity then sleeps: each tick the engine checks the
 * state is the expected one for the phase, writes the recorded after-tick values of the fields that vary, and
 * advances the clocks by their rates. It wakes on a block change in the region its ticks read, an entity event in the
 * boxes they read, a clock deadline (LabScheduler), or any unexpected field value. Code not executed during the
 * observed period is not executed while asleep (same state and inputs, same path).
 *
 * <p>{@code -Dlab.entsleep.diag=ItemEntity}: print what the fields of a few entities of that class do per tick.
 */
public final class EntitySleep {

    /**
     * The mutable state of entity sleep, one per side: the server thread's levels and the client's (the render thread,
     * OwnerThread.client). In singleplayer both tick at the same time - one shared set would race and mix the two
     * levels' plans, deadlines, subscriptions and observations. S() picks by the current thread.
     */
    static final class Side {
        int dbg, dbgP;
        long barrierHits, barrierChecked, barrierSyncs, barrierFull, barrierReread, barrierShared;
        Map<Class<?>, EClass> CLASSES = new java.util.concurrent.ConcurrentHashMap<>();   // filled by AsyncAnalysis
        Map<Class<?>, Integer> RETRIED = new java.util.concurrent.ConcurrentHashMap<>();
        long nextId;
        ES obs;
        Thread obsThread;
        long writes0, seed0, unob0, readHash;
        long unobservedE;
        long[] SB = new long[64], SA = new long[64];
        Object[] RB = new Object[64], RA = new Object[64];   // a mob's observed tick: the objects before and after
        long observedTicks, entered, replayed, verifyFails, deadlineWakes;
        Map<String, Long> WAKES = new TreeMap<>();
        Map<String, Long> REFUSED = new TreeMap<>();
        String mode;
        Map<String, long[]> TIMES = new TreeMap<>();
        long timedTick = -1, timedTicks, entityNs;   // all entity ticks timed, over how many server ticks
        long innerNs, innerN;
        long[] RT = new long[11];
        long[] OT = new long[24];
        long[] ST = new long[7];   // a mob snapshot: prims, refs, fingerprints (ns), count, slots fingerprinted again
        Map<String, long[]> FPT = new HashMap<>();
        Object[] FPR = new Object[64];
        long[] FIXED_RUNS = new long[8];   // run lengths 1, 2-3, 4-7, ... 128+, in ticks
        long fixedTicks, fixedAll;
        Map<String, Long> FIXED_BREAKERS = new TreeMap<>();
        long[] PROBE_CLOCKS = new long[32], PB = new long[64], PA = new long[64];
        Object[] PRB = new Object[64];
        long drawCheckFails;
        MobSleep.Lcg SCRATCH = new MobSleep.Lcg(0);
        long inlined, inlineFails, flipsPassed, preSkipped, inlineReplayed;
        Object[] REFS = new Object[64];
        LabScheduler DEADLINES = new LabScheduler();
        Long2ObjectOpenHashMap<ES> SCHEDULED = new Long2ObjectOpenHashMap<>();
        long lastPoll = Long.MIN_VALUE;
        Long2ObjectOpenHashMap<SubList> BLOCK_SUBS = new Long2ObjectOpenHashMap<>(), ENTITY_SUBS = new Long2ObjectOpenHashMap<>();
        Map<Class<?>, Field[]> DFIELDS = new HashMap<>();
        Map<Integer, long[]> DPREV = new HashMap<>();
        List<Integer> PICKED = new ArrayList<>();
    }

    static final Side SERVER = new Side(), CLIENT = new Side();

    /** this thread's side: the client's on the render thread, the server's anywhere else */
    static Side S() {
        return Thread.currentThread() == dev.lull.optsupport.OwnerThread.client ? CLIENT : SERVER;
    }

    public static final boolean ON = Boolean.getBoolean("lab.opt.EntitySleep");
    /**
     * -Dlab.opt.ClientEntitySleep=true (with EntitySleep): the client's entities sleep too (ClientEntSleepTickMixin) -
     * their own side (S()), classes analysed with isClientSide true; living entities (driven by the server's packets)
     * stay awake. A packet that moves or changes a sleeper leaves a state the replay does not expect: it wakes.
     */
    public static final boolean CLIENT_ON = ON && Boolean.getBoolean("lab.opt.ClientEntitySleep");
    public static final String DIAG = System.getProperty("lab.entsleep.diag");
    private static final boolean VERIFY = !Boolean.getBoolean("lab.entsleep.noVerify");
    private static final int MAX_PERIOD = Integer.getInteger("lab.entsleep.maxPeriod", 64);
    /** clone tests: only entities at x >= minX may sleep */
    private static final int MIN_X = Integer.getInteger("lab.entsleep.minX", Integer.MIN_VALUE);
    private static final int MAX_DIFFS = 8192;
    private static final boolean DEBUG = Boolean.getBoolean("lab.entsleep.debug");
    private static final int DEBUG_N = Integer.getInteger("lab.entsleep.debugN", 6);
    /** -Dlab.sleep.components=true: the analysis follows the entity's AI objects (mob sleep in progress, notes/47) */
    static final boolean COMPONENTS = Boolean.getBoolean("lab.sleep.components");
    /** -Dlab.entsleep.noSleepFor=A,B: these classes are observed but never put to sleep (diagnosis) */
    private static final java.util.Set<String> NO_SLEEP_FOR = java.util.Set.of(System.getProperty("lab.entsleep.noSleepFor", "").split(","));

    enum St { OBSERVE, ASLEEP, NEVER }

    /** Fields a replay may not write raw: position bookkeeping, identity, synched data (it has its own dirty tracking). */
    private static final Set<String> FORBID_V = Set.of("position", "blockPosition", "chunkPosition", "bb", "feetBlockState", "levelCallback",
            "removalReason", "entityData", "level", "xo", "yo", "zo", "xOld", "yOld", "zOld", "passengers", "vehicle", "uuid", "id");

    // ------------------------------------------------------------------ per class

    static final class EClass {
        final SleepAnalysis.Plan plan;
        String refuse;
        final Field[] f;
        final int n;
        SnapCompiler.Snapper snap;
        long analysisMs;
        int tickCount = -1;
        int[] clocks = new int[0];
        int[] nulls = new int[0], zeros = new int[0];
        int[] entityRefs = new int[0];
        boolean[] special;    // tickCount and clocks: compared by rule, not with the base value
        boolean[] prim;
        int[] refSlots;       // references the replay check compares (non-final), in order
        int[] refOrd;         // slot -> position in refSlots (-1: not compared)
        boolean[] forbidV;
        final int period;
        // mob sleep (MobSleep): the composite state, the plan's extras, the draw conditions by call site
        MobSleep.Composite comp;
        SleepAnalysis.MobPlan mob;
        Map<String, List<Integer>> drawSites;
        int entN;                     // slots of the entity itself (part 0)
        String[] slotPath;            // composite: the path of each slot's object
        int[] ctxEntityRefs = new int[0];   // context slots holding entities: null or this one
        int randomSlot = -1;
        Field randomField;
        int[] condSlot, condThreshold;   // per plan.conds(): the clock's slot and the threshold field's (-1)
        long[][] inline;              // {N, r}: ticks with tickCount % N == r run for real while asleep (checked after)
        int[] stillClocks = new int[0];   // clocks tested by a modulus: they must stand still
        MobSleep.Assumptions assume;  // the plan's assumptions compiled against the slots (made on first use)
        volatile long retryAt;        // refused for the sample's momentary state: analysed again from another one then (0 unset)
        String[] retryNull;           // ... one that holds null in these paths (the objects that refused the first)
        int[] clockPart;              // composite with Unsafe: per clock, its part and field offset and kind (0 int, 1 long, 2 short, 3 byte)
        long[] clockOff;
        byte[] clockKind;
        // the write barrier (BARRIER): every part's class has a watcher field, so writes into the parts mark their slots
        boolean barrier;
        long[] slotOff;               // per slot: its field's offset in its part (Unsafe)
        // draw conditions, per class (made on first use): the condition of each draw site id, each condition's clock or
        // the part holding its final field, the parts holding the assumed-empty collections
        int[] siteCond = new int[0];  // per DrawSiteIds id: the condition (-1 none, -2 two on one line, -3 not looked up)
        int[] condClock;              // per condition: index into clocks (-1: none)
        int[] condHolder;             // per condition: the part holding its field (-1: resolve condHolderPath)
        String[] condHolderPath;
        int[] emptyHolder;            // per assumed-empty key: its holder part (-1: resolve)
        String[] emptyHolderPath, emptyName;
        List<String> emptyKeys;

        int condOfSite(int site) {
            if (site >= siteCond.length) {
                int old = siteCond.length;
                siteCond = java.util.Arrays.copyOf(siteCond, Math.max(site + 1, DrawSiteIds.SITES.size()) + 16);
                java.util.Arrays.fill(siteCond, old, siteCond.length, -3);
            }
            int ci = siteCond[site];
            if (ci == -3) {
                List<Integer> cs = drawSites.get(MobSleep.SITES.get(site));
                siteCond[site] = ci = cs == null ? -1 : cs.size() != 1 ? -2 : cs.get(0);
            }
            return ci;
        }

        void prepDraws() {
            if (condClock != null) return;
            int nd = mob.draws().size();
            String[] pp = new String[comp.parts.length];
            for (int k = 0; k < pp.length; k++) pp[k] = comp.parts[k].path;
            List<String> partPaths = java.util.Arrays.asList(pp);
            int[] cc = new int[nd], ch = new int[nd];
            String[] chp = new String[nd];
            for (int ci = 0; ci < nd; ci++) {
                SleepAnalysis.DrawCond d = mob.draws().get(ci);
                cc[ci] = -1;
                String path = d.path() == null ? "" : d.path();
                int dot = path.lastIndexOf('.');
                String hp = dot < 0 ? "" : path.substring(0, dot);
                chp[ci] = hp;
                ch[ci] = partPaths.indexOf(hp);
                if (d.other() != SleepAnalysis.DrawCond.CONST && d.other() != SleepAnalysis.DrawCond.FINAL && d.field() != null) {
                    int slot = slot(hp, d.field());
                    for (int q = 0; q < clocks.length; q++) if (clocks[q] == slot) cc[ci] = q;
                }
            }
            List<String> em = new ArrayList<>(mob.empty());
            int[] eh = new int[em.size()];
            String[] ehp = new String[em.size()], en = new String[em.size()];
            for (int q = 0; q < eh.length; q++) {
                String k = em.get(q);
                int dot = k.lastIndexOf('.');
                ehp[q] = dot < 0 ? "" : k.substring(0, dot);
                en[q] = dot < 0 ? k : k.substring(dot + 1);
                eh[q] = partPaths.indexOf(ehp[q]);
            }
            condHolder = ch;
            condHolderPath = chp;
            emptyHolder = eh;
            emptyHolderPath = ehp;
            emptyName = en;
            emptyKeys = em;
            condClock = cc;
        }

        /** the object at a holder path: the part when it is one, else resolved from the entity */
        Object holderOf(ES s, int part, String path) {
            if (part >= 0 && s.ctx != null) return s.ctx[part];
            return path.isEmpty() ? s.e : MobSleep.resolve(s.e, path);
        }

        PartSlots[] partSlots;        // per part: barrier field id -> slot
        byte[] slotKind;              // per primitive slot: Z B C S I F J D as 0..7

        /** a primitive slot's bits as the snapshots give them (SnapCompiler.toBits), straight from its part */
        long slotBits(Object[] o, int i) {
            Object x = o[comp.partOf[i]];
            long off = slotOff[i];
            sun.misc.Unsafe u = SnapCompiler.U;
            return switch (slotKind[i]) {
                case 0 -> u.getBoolean(x, off) ? 1 : 0;
                case 1 -> u.getByte(x, off);
                case 2 -> u.getChar(x, off);
                case 3 -> u.getShort(x, off);
                case 4 -> u.getInt(x, off);
                case 5 -> Float.floatToRawIntBits(u.getFloat(x, off));
                case 6 -> u.getLong(x, off);
                default -> Double.doubleToRawLongBits(u.getDouble(x, off));
            };
        }
        int[] clockOf;                // per slot: its index in clocks (-1 not a clock)

        int lookupSlot(int k, int id) {
            MobSleep.Part pt = comp.parts[k];
            String name = WBIds.name[id];
            Class<?> owner;
            try {
                owner = Class.forName(WBIds.owner[id].replace('/', '.'), false, EntitySleep.class.getClassLoader());
            } catch (ClassNotFoundException ex) {
                return -1;
            }
            // the field the site names: declared in its owner class or the nearest superclass declaring that name
            int best = -1;
            for (int i = pt.off; i < pt.off + pt.f.length; i++) {
                Field x = f[i];
                if (!x.getName().equals(name) || !x.getDeclaringClass().isAssignableFrom(owner)) continue;
                if (best < 0 || f[best].getDeclaringClass().isAssignableFrom(x.getDeclaringClass())) best = i;
            }
            return best;
        }

        boolean special(int tickCount) {
            if (inline == null) return false;
            for (long[] m : inline) if (Math.floorMod(tickCount, m[0]) == m[1]) return true;
            return false;
        }

        /** which of the inline moduli this tick matches (bit k: inline[k]) */
        int specialMask(int tickCount) {
            int mask = 0;
            for (int k = 0; k < inline.length && k < 31; k++) if (Math.floorMod(tickCount, inline[k][0]) == inline[k][1]) mask |= 1 << k;
            return inline.length > 31 ? -1 : mask;
        }

        /** the reference in slot i (the entity's field, or a context's) */
        Object ref(int i, ES s) {
            if (comp == null) return get(f[i], s.e);
            return get(f[i], s.ctx[comp.partOf[i]]);
        }

        long hashOf(long[] v) {
            return comp == null ? SnapCompiler.hashPrims(v, prim, special) : comp.hashOf(v);
        }

        int slot(String path, Field x) {
            for (int i = 0; i < n; i++) if (f[i].equals(x) && (slotPath == null ? path.isEmpty() : slotPath[i].equals(path))) return i;
            return -1;
        }

        EClass(Class<?> c) {
            this(c, null);
        }

        /** sample: an instance whose AI objects the analysis follows (-Dlab.sleep.components=true, notes/47) */
        EClass(Class<?> c, Object sample) {
            this(c, sample, false);
        }

        /** client: for the client's levels (SleepAnalysis' client mode) */
        EClass(Class<?> c, Object sample, boolean client) {
            long t0 = System.nanoTime();
            plan = SleepAnalysis.analyzeEntity(c, COMPONENTS ? sample : null, client);
            analysisMs = (System.nanoTime() - t0) / 1_000_000;
            List<Field> fl = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field x : k.getDeclaredFields()) {
                    if (x.getName().equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (Modifier.isStatic(x.getModifiers())) continue;
                    try {
                        x.setAccessible(true);
                        fl.add(x);
                    } catch (RuntimeException e) {
                        refuse = "field not accessible: " + x.getName();
                    }
                }
            }
            entN = fl.size();
            List<String> paths = new ArrayList<>();
            List<Integer> partStart = new ArrayList<>();
            List<Class<?>> partClass = new ArrayList<>();
            if (plan.mob() != null && MobSleep.ON && plan.eligible()) {
                mob = plan.mob();
                partStart.add(0);
                partClass.add(c);
                paths.add("");
                List<String> sp = new ArrayList<>();
                for (int i = 0; i < entN; i++) sp.add("");
                for (String path : mob.contexts()) {
                    Object o = MobSleep.resolve(sample, path);
                    if (o == null) {
                        refuse = "context " + path + " missing on the sample";
                        break;
                    }
                    partStart.add(fl.size());
                    partClass.add(o.getClass());
                    paths.add(path);
                    // an object with a modification counter the replay checks (the attribute map): its fields are not
                    // state to snapshot
                    boolean epochObject = o instanceof MoveMemo.AttrEpoch;
                    for (Field x : MobSleep.instanceFields(o.getClass())) {
                        Class<?> t = x.getType();
                        if (epochObject) continue;
                        // GoalSelector's goal set: only addGoal / removeGoal change it, and they change its counter
                        if (o instanceof MobSleepHooks.GoalEpoch && x.getName().equals("availableGoals")) continue;
                        if (Modifier.isFinal(x.getModifiers()) && !t.isPrimitive() && !t.isArray() && !java.util.Collection.class.isAssignableFrom(t)
                                && !java.util.Map.class.isAssignableFrom(t)) continue;   // the same object for life (a context has its own part)
                        fl.add(x);
                        sp.add(path);
                    }
                }
                slotPath = sp.toArray(new String[0]);
                drawSites = MobSleep.sitesOf(mob.draws());
                randomField = MobSleep.field(Entity.class, "random");
            }
            f = fl.toArray(new Field[0]);
            n = f.length;
            period = (int) Math.min(Integer.MAX_VALUE, plan.period());
            if (!plan.eligible()) refuse = plan.reason();
            else if (period > MAX_PERIOD) refuse = "period " + period + " > " + MAX_PERIOD;
            else if (!EntityFp.available()) refuse = "fingerprints unavailable";
            if (refuse != null) return;
            special = new boolean[n];
            forbidV = new boolean[n];
            prim = new boolean[n];
            for (int i = 0; i < n; i++) prim[i] = f[i].getType().isPrimitive();
            List<Integer> cl = new ArrayList<>(), nu = new ArrayList<>(), er = new ArrayList<>(), ze = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Field x = f[i];
                if (x.getName().equals("tickCount") && x.getDeclaringClass() == Entity.class) {
                    tickCount = i;
                    special[i] = true;
                    continue;
                }
                boolean isClock = false;
                if (mob != null) {
                    for (int j = 0; j < plan.clocks().size(); j++)
                        if (plan.clocks().get(j).equals(x) && mob.clockPaths().get(j).equals(slotPath[i])) isClock = true;
                    if (x.equals(randomField) && slotPath[i].isEmpty()) {
                        randomSlot = i;
                        special[i] = true;
                        continue;
                    }
                    if (i >= entN) {
                        if (Entity.class.isAssignableFrom(x.getType())) ctxEntityRefs = java.util.Arrays.copyOf(ctxEntityRefs, ctxEntityRefs.length + 1);
                        if (Entity.class.isAssignableFrom(x.getType())) ctxEntityRefs[ctxEntityRefs.length - 1] = i;
                        if (isClock) {
                            if (!(x.getType() == int.class || x.getType() == long.class || x.getType() == short.class || x.getType() == byte.class)) {
                                refuse = "clock of type " + x.getType();
                                return;
                            }
                            cl.add(i);
                            special[i] = true;
                        }
                        continue;
                    }
                } else {
                    isClock = plan.clocks().contains(x);
                }
                if (isClock) {
                    if (!(x.getType() == int.class || x.getType() == long.class || x.getType() == short.class || x.getType() == byte.class)) {
                        refuse = "clock of type " + x.getType();
                        return;
                    }
                    cl.add(i);
                    special[i] = true;
                }
                if (plan.nulls().contains(x)) nu.add(i);
                if (plan.zeros().contains(x)) ze.add(i);
                if (Entity.class.isAssignableFrom(x.getType())) er.add(i);
                forbidV[i] = FORBID_V.contains(x.getName()) && x.getDeclaringClass() == Entity.class;
            }
            if (tickCount < 0) refuse = "no tickCount";
            if (refuse != null) return;
            if (mob != null) {
                // one compiled snapper per part, laid end to end
                MobSleep.Part[] parts = new MobSleep.Part[paths.size()];
                int refOff = 0;
                for (int k = 0; k < parts.length; k++) {
                    int a0 = partStart.get(k), a1 = k + 1 < parts.length ? partStart.get(k + 1) : n;
                    parts[k] = new MobSleep.Part(paths.get(k), partClass.get(k), fl.subList(a0, a1), a0, java.util.Arrays.copyOfRange(special, a0, a1), refOff);
                    refOff += parts[k].refSlots.length;
                }
                comp = new MobSleep.Composite(parts, fl, special);
                snap = comp;
                refSlots = new int[refOff];
                for (MobSleep.Part pt : parts) for (int k = 0; k < pt.refSlots.length; k++) refSlots[pt.refOff + k] = pt.off + pt.refSlots[k];
                inline = mob.inlineTicks().isEmpty() ? null : mob.inlineTicks().toArray(new long[0][]);
                List<Integer> st = new ArrayList<>();
                for (String k : mob.stillClocks()) {
                    int dot = k.lastIndexOf('.');
                    String hp = dot < 0 ? "" : k.substring(0, dot), fn = dot < 0 ? k : k.substring(dot + 1);
                    int found = -1;
                    for (int i = 0; i < n; i++) if (slotPath[i].equals(hp) && f[i].getName().equals(fn)) found = i;
                    if (found < 0) {
                        refuse = "still clock not found: " + k;
                        return;
                    }
                    st.add(found);
                }
                stillClocks = st.stream().mapToInt(Integer::intValue).toArray();
                condSlot = new int[plan.conds().size()];
                condThreshold = new int[plan.conds().size()];
                for (int j = 0; j < condSlot.length; j++) {
                    SleepAnalysis.Cond cd = plan.conds().get(j);
                    String cp = mob.condPaths().get(j);
                    condSlot[j] = cd.clock() == null ? -1 : slot(cp, cd.clock());
                    condThreshold[j] = cd.thresholdField() == null ? -1 : slot(cp, cd.thresholdField());
                }
            } else {
                // one compiled class: snap() by value for observation, same() by value/identity for the replay check
                snap = SnapCompiler.compile(fl, true, special);
                refSlots = SnapCompiler.refSlots(fl, special);
            }
            refOrd = new int[n];
            java.util.Arrays.fill(refOrd, -1);
            for (int k = 0; k < refSlots.length; k++) refOrd[refSlots[k]] = k;
            if (SnapCompiler.failure != null) System.out.println("[sleep/entsleep] snapshot fallback (reflection): " + SnapCompiler.failure);
            clocks = cl.stream().mapToInt(Integer::intValue).toArray();
            if (comp != null && SnapCompiler.U != null) {
                clockPart = new int[clocks.length];
                clockOff = new long[clocks.length];
                clockKind = new byte[clocks.length];
                for (int j = 0; j < clocks.length; j++) {
                    Field x = f[clocks[j]];
                    clockPart[j] = comp.partOf[clocks[j]];
                    clockOff[j] = SnapCompiler.U.objectFieldOffset(x);
                    Class<?> t = x.getType();
                    clockKind[j] = (byte) (t == int.class ? 0 : t == long.class ? 1 : t == short.class ? 2 : 3);
                }
            }
            nulls = nu.stream().mapToInt(Integer::intValue).toArray();
            zeros = ze.stream().mapToInt(Integer::intValue).toArray();
            entityRefs = er.stream().mapToInt(Integer::intValue).toArray();
            if (BARRIER && comp != null && comp.all != null && SnapCompiler.U != null) {
                barrier = true;
                for (MobSleep.Part pt : comp.parts) if (WB.rootIndex(pt.cls) < 0) {
                    barrier = false;
                    System.out.println("[sleep/entsleep] " + c.getSimpleName() + ": no write barrier, part " + pt.path + " (" + pt.cls.getSimpleName() + ") has no watcher");
                }
                if (barrier) {
                    slotOff = new long[n];
                    slotKind = new byte[n];
                    for (int i = 0; i < n; i++) {
                        slotOff[i] = SnapCompiler.U.objectFieldOffset(f[i]);
                        Class<?> t = f[i].getType();
                        slotKind[i] = (byte) (t == boolean.class ? 0 : t == byte.class ? 1 : t == char.class ? 2 : t == short.class ? 3 : t == int.class ? 4
                                : t == float.class ? 5 : t == long.class ? 6 : 7);
                    }
                    partSlots = new PartSlots[comp.parts.length];
                    for (int k = 0; k < partSlots.length; k++) partSlots[k] = new PartSlots(this, k);
                    clockOf = new int[n];
                    java.util.Arrays.fill(clockOf, -1);
                    for (int j = 0; j < clocks.length; j++) clockOf[clocks[j]] = j;
                }
            }
        }
    }

    /**
     * The write barrier (-Dlab.opt.WriteBarrier=true: WriteBarrier, WB). A mob's parts carry a PartWatch: every write
     * into them (the mob's own code, the level's, another entity's) marks its slot dirty, in two masks. The replay checks
     * only the slots in {@code vdirty} (none written: nothing to check - the furnace model), observation keeps a mirror
     * of the state and re-reads only the slots in {@code mdirty} (and containers, whose content changes without a write
     * into a part) instead of snapshotting all of them. -Dlab.entsleep.barrier=false: the snapshots as before.
     */
    static final boolean BARRIER = Boolean.getBoolean("lab.opt.WriteBarrier") && !"false".equals(System.getProperty("lab.entsleep.barrier"));

    /**
     * A part's barrier field ids -> slots, shared by the class's entities: a small open-addressing table (the ids a part
     * is written through are a few dozen; a table indexed by id spread over ~1000 ids cost a cache miss per write).
     */
    static final class PartSlots {
        final EClass c;
        final int k;
        int[] keys = new int[64], vals = new int[64];
        int size;

        PartSlots(EClass c, int k) {
            this.c = c;
            this.k = k;
            java.util.Arrays.fill(keys, -1);
        }

        int get(int id) {
            int[] ks = keys;
            int m = ks.length - 1;
            for (int p = (id * 0x9E3779B1) >>> 16 & m; ; p = p + 1 & m) {
                int x = ks[p];
                if (x == id) return vals[p];
                if (x == -1) return miss(id);
            }
        }

        private int miss(int id) {
            int slot = c.lookupSlot(k, id);
            if (size * 2 >= keys.length) {
                int[] ok = keys, ov = vals;
                keys = new int[ok.length * 2];
                vals = new int[ok.length * 2];
                java.util.Arrays.fill(keys, -1);
                size = 0;
                for (int i = 0; i < ok.length; i++) if (ok[i] != -1) put(ok[i], ov[i]);
            }
            put(id, slot);
            return slot;
        }

        private void put(int id, int slot) {
            int m = keys.length - 1;
            int p = (id * 0x9E3779B1) >>> 16 & m;
            while (keys[p] != -1) p = p + 1 & m;
            keys[p] = id;
            vals[p] = slot;
            size++;
        }
    }

    /** a mob part's watcher (WB): writes into the part mark their slots dirty */
    static final class PartWatch {
        final ES s;
        final int k;
        final PartSlots slots;
        final long[] vd, md;

        PartWatch(ES s, int k) {
            this.s = s;
            this.k = k;
            slots = s.c.partSlots[k];
            vd = s.vdirty;
            md = s.mdirty;
        }

        void write(int id) {
            int i = slots.get(id);
            if (i >= 0) {
                long bit = 1L << i;
                vd[i >>> 6] |= bit;
                md[i >>> 6] |= bit;
            }
        }
    }

    /**
     * The watcher of a part several entities share (the type's AttributeSupplier): a write goes to every one of them.
     * Entries of removed entities are dropped when one is added.
     */
    static final class SharedWatch {
        PartWatch[] w = new PartWatch[4];
        int n;

        void add(PartWatch pw) {
            for (int i = 0; i < n; i++) if (w[i] == pw) return;
            int k = 0;
            for (int i = 0; i < n; i++) if (!w[i].s.e.isRemoved() && w[i].s.barrierOn) w[k++] = w[i];
            for (int i = k; i < n; i++) w[i] = null;
            n = k;
            if (n == w.length) w = java.util.Arrays.copyOf(w, n * 2);
            w[n++] = pw;
        }

        void remove(PartWatch pw) {
            for (int i = 0; i < n; i++)
                if (w[i] == pw) {
                    w[i] = w[--n];
                    w[n] = null;
                    return;
                }
        }
    }


    static void onWrite(SharedWatch sw, int id) {
        for (int i = 0; i < sw.n; i++) sw.w[i].write(id);
    }

    /** puts the ES's watchers on its parts (s.ctx); a part already watched by another entity: no barrier for this one */
    private static void watchParts(ES s) {
        Object[] o = s.ctx;
        int n = s.c.n;
        if (s.watches == null) {
            s.vdirty = new long[(n + 63) >>> 6];
            s.mdirty = new long[(n + 63) >>> 6];
            s.watches = new PartWatch[o.length];
            for (int k = 0; k < o.length; k++) s.watches[k] = new PartWatch(s, k);
            s.M = new long[n];
            s.MR = new Object[n];
        }
        boolean same = s.watched != null && s.watched.length == o.length;
        for (int k = 0; same && k < o.length; k++) same = s.watched[k] == o[k];
        if (same) return;
        if (s.watched != null)
            for (Object x : s.watched) {
                Object w = WB.watcherOf(x);
                if (w instanceof PartWatch pw && pw.s == s) WB.watch(x, null);
                else if (w instanceof SharedWatch sw) for (PartWatch pw : s.watches) sw.remove(pw);
            }
        for (int k = 0; k < o.length; k++) {
            Object w = WB.watcherOf(o[k]);
            PartWatch mine = s.watches[k];
            if (w == null || w == mine || w instanceof PartWatch pw && (pw.s == s || pw.s.e.isRemoved() || !pw.s.barrierOn)) {
                WB.watch(o[k], mine);
            } else if (w instanceof SharedWatch sw) {
                sw.add(mine);
            } else if (w instanceof PartWatch other) {   // shared with another entity: both hear its writes
                SharedWatch sw = new SharedWatch();
                sw.add(other);
                sw.add(mine);
                WB.watch(o[k], sw);
                S().barrierShared++;
            } else {   // watched by something else (the measurement mode): snapshots
                s.barrierOn = false;
                return;
            }
        }
        s.watched = o.clone();
        s.mValid = false;
    }

    /**
     * Brings the mirror (s.M fingerprints, s.MR objects) up to date: the slots written since the last sync, and with
     * {@code containers} the containers (their content changes without a write into a part; the probe compares only
     * primitives, a window's state before a tick is the last one's after-state). True if a primitive other than the
     * clocks changed (or the mirror was rebuilt).
     */
    private static boolean sync(ES s, boolean containers) {
        EClass c = s.c;
        S().barrierSyncs++;
        if (!s.mValid) {
            if (s.content == null || s.content.length != c.n) s.content = new boolean[c.n];
            c.comp.snapObserved(s.e, s.M, s.MR, null, null, s.content, false);
            java.util.Arrays.fill(s.mdirty, 0);
            s.mValid = true;
            S().barrierFull++;
            return true;
        }
        Object[] o = s.ctx;
        boolean changed = false;
        long[] d = s.mdirty, m = s.M;
        Object[] mr = s.MR;
        boolean[] prim = c.prim, special = c.special, content = s.content;
        for (int w = 0; w < d.length; w++) {
            long bits = d[w];
            if (bits == 0) continue;
            d[w] = 0;
            while (bits != 0) {
                int i = (w << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                S().barrierReread++;
                if (prim[i]) {
                    long x = c.slotBits(o, i);
                    if (x != m[i]) {
                        if (!special[i]) changed = true;
                        m[i] = x;
                    }
                } else {
                    Object r = SnapCompiler.U.getObject(o[c.comp.partOf[i]], c.slotOff[i]);
                    if (r != mr[i]) {
                        mr[i] = r;
                        content[i] = EntityFp.content(r);
                        m[i] = EntityFp.observedFp(r);
                    }
                }
            }
        }
        if (containers) for (int i : c.comp.refAll) if (content[i]) m[i] = EntityFp.observedFp(mr[i]);
        return changed;
    }

    /** asleep: the slots written since the last replay, against what the phase expects; null if all as expected */
    private static String dirtyCheck(ES s, int p) {
        EClass c = s.c;
        long[] d = s.vdirty;
        Object[] o = s.ctx;
        for (int w = 0; w < d.length; w++) {
            long bits = d[w];
            if (bits == 0) continue;
            d[w] = 0;
            while (bits != 0) {
                int i = (w << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                S().barrierChecked++;
                if (i == c.tickCount || i == c.randomSlot) continue;   // the level's count; the seed is checked apart
                int j = c.clockOf[i];
                if (j >= 0) {
                    if (clockBits(s, j) != s.expClock[j]) return "clock " + slotName(c, i);
                    continue;
                }
                if (c.prim[i]) {
                    int vj = java.util.Arrays.binarySearch(s.v, i);
                    long exp = vj >= 0 ? s.bV[p][vj] : s.expPrim[i];
                    if (c.snap.bits(s.e, i) != exp) return slotName(c, i);
                } else {
                    int k = c.refOrd[i];
                    if (k >= 0 && SnapCompiler.U.getObject(o[c.comp.partOf[i]], c.slotOff[i]) != s.expR[k]) return slotName(c, i);
                }
            }
        }
        return null;
    }

    private static void markDirty(long[] d, int i) {
        d[i >>> 6] |= 1L << i;
    }

    private static final java.util.regex.Pattern HELD = java.util.regex.Pattern.compile("assumes ([^ ]+) null, the sample holds one"),
            REASONS = java.util.regex.Pattern.compile("([0-9]+) reasons");

    /**
     * The paths whose objects alone refused a class ("assumes X null, the sample holds one" for every reason): another
     * entity holding none of them is a sample worth analysing again. Null if any other reason refused it.
     */
    static String[] sampleNulls(String refuse) {
        java.util.regex.Matcher r = REASONS.matcher(refuse);
        if (!r.find()) return null;
        int n = Integer.parseInt(r.group(1));
        List<String> paths = new ArrayList<>();
        java.util.regex.Matcher m = HELD.matcher(refuse);
        while (m.find()) paths.add(m.group(1));
        return n > 0 && paths.size() == n ? paths.toArray(new String[0]) : null;
    }

    private static boolean allNull(Entity e, String[] paths) {
        for (String p : paths) if (MobSleep.resolve(e, p) != null) return false;
        return true;
    }
    private static final int ANALYSIS_RETRIES = Integer.getInteger("lab.entsleep.analysisRetries", 5);

    // ------------------------------------------------------------------ per entity

    static final class ES {
        final Entity e;
        final EClass c;
        final long id;
        St st = St.OBSERVE;
        // observation window
        int k = -1;
        int lastTick, startTick;
        long[] base, prevA, rates;
        Object[] prevRefs;            // a mob's: the objects of prevA
        boolean[] content;            // a mob's: per slot, whether the object last snapshotted there is a container
        Object[] baseRefs;
        LongArrayList diffs;          // pairs: ((k * 2 + after) << 16 | field), value
        ArrayList<Object> diffRefs;   // the object behind each after-diff of a reference field (null otherwise)
        long[] readHashAt;
        AABB blockBox, entityBox;     // union of the window's reads
        boolean entityExposed;        // the tick got entity objects (getEntities): neighbour rule
        int failStreak;
        int[] inlineOk = new int[4];  // asleep: the sets of moduli whose tick ran for real and passed in this sleep
        int inlineOkN;
        boolean probeOk;              // a mob's probe tick left its primitives unchanged: the next tick starts a window
        int probeFails, probeFrom;    // probes failed in a row; the tick of the next probe
        int fixedRun;                 // -Dlab.entsleep.fixedStat: the current run of fixed-point ticks
        long[] fixedRates;
        int observeFrom;              // tickCount before which it is not observed (back-off)
        // asleep
        int[] v;
        long[][] aV, bV;
        Object[][] aRef;
        long[] hashB;                 // per phase: hash of the primitives expected before the tick
        Object[] expR;                // expected references (identity), compact (EClass.refSlots)
        long[] expClock;
        long wakeAt;
        boolean woken;
        String wakeReason;
        int subEpoch;
        // mob sleep
        Object[] ctx;                 // the composite's objects (part 0: the entity)
        LongArrayList[] drawLog;      // per observed tick of the window: the draws (key, value) pairs
        int[][] dKind, dBound, dCond; // asleep: per phase, the draws to replay and their conditions
        boolean[][] dOut;             // per phase and draw: the branch the observed tick took
        net.minecraft.util.RandomSource random;
        java.util.concurrent.atomic.AtomicLong seedRef;
        long expSeed;
        int attrEpoch = Integer.MIN_VALUE, goalEpoch0, goalEpoch1;
        boolean readsWeather, readsPlayers;   // the window read them (observed while there is none)
        long[][] afterExp;            // per phase: the whole state a tick leaves (an inline tick is checked against it)
        Object[] emptyObjs;           // asleep: the collections assumed empty
        long clockWakeAt = Long.MAX_VALUE;   // the clock conditions' deadline (absolute game time)
        boolean drawWake;             // wakeAt is a draw's: that tick runs for real and the sleep may go on
        MoveMemo.AttrEpoch attrObj;
        MobSleepHooks.GoalEpoch goal0, goal1;
        double[][] dConst;            // per phase and draw: the other side's constant part (constant, final field, clock offset)
        int[][] dClock;               // ... and its clock (index into EClass.clocks, -1 none)
        long[] clockBase;             // per clock: its value when the window began
        // write barrier
        boolean barrierOn;            // its parts are watched (EClass.barrier, not shared with another entity)
        PartWatch[] watches;
        Object[] watched;             // the objects the watchers are on
        long[] vdirty, mdirty;        // slots written since the replay's last check / since the mirror's last sync
        long[] M;                     // the mirror: fingerprints as snapObserved gives them
        Object[] MR;                  // ... and the objects
        boolean mValid;
        long[] expPrim;               // asleep: the state the sleep began with (a slot outside v keeps its value)

        ES(Entity e, EClass c) {
            this.e = e;
            this.c = c;
            this.id = ++S().nextId;
        }

        boolean inlinePassed(int mask) {
            if (mask == -1) return false;
            for (int i = 0; i < inlineOkN; i++) if (inlineOk[i] == mask) return true;
            return false;
        }

        void addInlinePassed(int mask) {
            if (mask == -1 || inlinePassed(mask)) return;
            if (inlineOkN == inlineOk.length) inlineOk = java.util.Arrays.copyOf(inlineOk, inlineOkN * 2);
            inlineOk[inlineOkN++] = mask;
        }
    }


    // stats

    private EntitySleep() {}

    private static ES es(Entity e) {
        return (ES) ((EntSleeperHolder) e).sleep$es();
    }

    static ES esOf(Entity e) {
        return es(e);
    }

    // ------------------------------------------------------------------ the tick

    /** -Dlab.entsleep.trace=<entity id>: one line per tick of that entity (mode, clocks, rotations, random seed) */
    private static final int TRACE = Integer.getInteger("lab.entsleep.trace", -1);
    /** -Dlab.entsleep.timing: ticks and nanoseconds per entity class and mode (never / observe / replay / inline / flip), and the vanilla tick inside an observed one */
    static final boolean TIMING = Boolean.getBoolean("lab.entsleep.timing");
    /** -Dlab.entsleep.timingFrom=<game time>: the per-mode times only from then on (the steady state) */
    private static final long TIMING_FROM = Long.getLong("lab.entsleep.timingFrom", 0);

    /** -Dlab.entsleep.timing: the value fingerprint's cost per reference slot (every 64th observed tick) */
    private static void fpTimes(ES s) {
        EClass c = s.c;
        if (S().FPR.length < c.n) S().FPR = new Object[c.n * 2];
        c.snap.refs(s.e, S().FPR);
        for (int i = 0; i < c.n; i++) {
            if (c.prim[i]) continue;
            Object o = S().FPR[i];
            long t0 = System.nanoTime();
            long x = EntityFp.fp(o);
            long dt = System.nanoTime() - t0;
            for (int rep = 0; rep < 2; rep++) {   // the same again: the cost without first touches
                long t1 = System.nanoTime();
                x += EntityFp.fp(o);
                dt = Math.min(dt, System.nanoTime() - t1);
            }
            long[] v = S().FPT.computeIfAbsent(slotName(c, i) + " (" + (o == null ? "null" : o.getClass().getSimpleName()) + ")", k -> new long[3]);
            v[0]++;
            v[1] += dt;
            v[2] += x & 1;
        }
    }   // observe: snap before, snap after (ns), count, draw sites (ns), draws   // replay: verify, checks, writes, draws (ns), count, varying fields, draws
    /**
     * -Dlab.entsleep.fixedStat: no sleep, every mob tick observed; counts the ticks at a fixed point (only clocks
     * changed, at last tick's rates, and the entity's random) and the lengths of their runs
     */
    private static final boolean FIXED_STAT = Boolean.getBoolean("lab.entsleep.fixedStat");

    private static void fixedStat(ES s, long[] b, long[] a, boolean quiet) {
        EClass c = s.c;
        S().fixedAll++;
        String breaker = quiet ? null : "busy";
        for (int i = 0; i < c.n && breaker == null; i++) if (!c.special[i] && a[i] != b[i]) breaker = slotName(c, i);
        long[] r = s.fixedRates;
        if (r == null || r.length != c.clocks.length) r = s.fixedRates = new long[c.clocks.length];
        for (int j = 0; j < c.clocks.length; j++) {
            long d = a[c.clocks[j]] - b[c.clocks[j]];
            if (breaker == null && d != r[j]) breaker = "rate " + slotName(c, c.clocks[j]);
            r[j] = d;
        }
        if (breaker == null) {
            S().fixedTicks++;
            s.fixedRun++;
            return;
        }
        S().FIXED_BREAKERS.merge(breaker, 1L, Long::sum);
        if (s.fixedRun > 0) S().FIXED_RUNS[Math.min(7, 63 - Long.numberOfLeadingZeros(s.fixedRun))] += s.fixedRun;
        s.fixedRun = 0;
    }

    /** Replaces the entity.tick() call in ServerLevel.tickNonPassenger. */
    public static void tick(Entity e) {
        if (TIMING && e.level().getGameTime() >= TIMING_FROM) {
            S().mode = "never";
            long t0 = System.nanoTime();
            tick0(e);
            long dt = System.nanoTime() - t0;
            long[] b = S().TIMES.computeIfAbsent(e.getClass().getSimpleName() + " " + S().mode, k -> new long[2]);
            b[0]++;
            b[1] += dt;
            long gt = e.level().getGameTime();
            if (gt != S().timedTick) {
                S().timedTick = gt;
                S().timedTicks++;
            }
            S().entityNs += dt;
            return;
        }
        if (TRACE < 0 || e.getId() != TRACE) {
            tick0(e);
            return;
        }
        S().mode = "vanilla";
        ES s0 = es(e);
        String before = s0 == null ? "-" : s0.st.toString();
        MobSleep.TICK.clear();
        MobSleep.REC = MobSleep.fieldValue(e, "random");
        try {
            tick0(e);
        } finally {
            MobSleep.REC = null;
        }
        java.util.concurrent.atomic.AtomicLong sd = MobSleep.seedOf(MobSleep.fieldValue(e, "random"));
        System.out.println("[sleep/trace] t=" + e.tickCount + " " + before + " " + S().mode + " amb=" + MobSleep.fieldValue(e, "ambientSoundTime") + " head=" + e.getYHeadRot()
                + " body=" + (e instanceof net.minecraft.world.entity.LivingEntity le ? le.yBodyRot : 0) + " seed=" + (sd == null ? "?" : Long.toHexString(sd.get()))
                + " pos=" + e.position() + " dm=" + e.getDeltaMovement() + " run=" + traceGoals(e) + " draws=" + traceDraws());
    }

    private static String traceGoals(Entity e) {
        Object sel = MobSleep.fieldValue(e, "goalSelector");
        if (!(sel instanceof net.minecraft.world.entity.ai.goal.GoalSelector gs)) return "-";
        StringBuilder sb = new StringBuilder();
        for (var w : gs.getAvailableGoals()) if (w.isRunning()) sb.append(w.getGoal().getClass().getSimpleName()).append(' ');
        Object locked = MobSleep.fieldValue(gs, "lockedFlags"), disabled = MobSleep.fieldValue(gs, "disabledFlags");
        String lk = "";
        if (locked instanceof java.util.Map<?, ?> lm) for (var en : lm.entrySet())
            lk += en.getKey() + ">" + ((net.minecraft.world.entity.ai.goal.WrappedGoal) en.getValue()).getGoal().getClass().getSimpleName() + ((net.minecraft.world.entity.ai.goal.WrappedGoal) en.getValue()).isRunning() + "/";
        return sb.toString().trim().replace(' ', ',') + " locked=" + lk + " disabled=" + disabled;
    }

    private static String traceDraws() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MobSleep.TICK.size(); i += 2) {
            String site = MobSleep.SITES.get(MobSleep.siteOf(MobSleep.TICK.getLong(i)));
            sb.append(site.substring(site.lastIndexOf('/') + 1)).append(' ');
        }
        MobSleep.TICK.clear();
        return sb.toString();
    }

    private static void tick0(Entity e) {
        if (DIAG != null && e.getClass().getSimpleName().equals(DIAG)) {
            diag(e);
            return;
        }
        if (!ON) {
            e.tick();
            return;
        }
        Level level = e.level();
        long now = level.getGameTime();
        if (now != S().lastPoll) poll(now);
        ES s = es(e);
        if (s != null && s.st == St.NEVER && s.c.retryAt > 0) {
            // its class was refused for the sample's momentary state: analysed again from another entity, whose
            // result replaces the refusal for all of them
            EClass cur = S().CLASSES.get(e.getClass());
            if (cur != null && cur != s.c) s = null;
            else if (cur == s.c && s.c.retryAt != Long.MAX_VALUE && now >= s.c.retryAt && allNull(e, s.c.retryNull)) {
                s.c.retryAt = Long.MAX_VALUE;
                if (S().RETRIED.merge(e.getClass(), 1, Integer::sum) <= ANALYSIS_RETRIES) {
                    S().CLASSES.remove(e.getClass(), s.c);
                    s = null;
                }
            }
        }
        if (s == null) {
            EClass c = AsyncAnalysis.get(S().CLASSES, e.getClass(), k -> {
                EClass x = new EClass(k, e, e.level().isClientSide);
                System.out.println("[sleep/entsleep] " + k.getSimpleName() + " (" + x.analysisMs + " ms): " + (x.refuse == null ? "ELIGIBLE fields " + x.n + " (final "
                        + java.util.Arrays.stream(x.f).filter(q -> Modifier.isFinal(q.getModifiers())).count() + ", primitive "
                        + java.util.Arrays.stream(x.f).filter(q -> q.getType().isPrimitive()).count() + ") period " + x.period
                        + " clocks=" + java.util.Arrays.stream(x.clocks).mapToObj(i -> x.f[i].getName()).toList()
                        + " conds=" + x.plan.conds().stream().map(SleepAnalysis.Cond::text).toList() : "no: " + x.refuse));
                return x;
            });
            if (c == null) {   // its class is being analysed in the background: vanilla's tick meanwhile
                e.tick();
                return;
            }
            if (c.refuse != null && c.mob == null && c.retryAt == 0 && COMPONENTS && (c.retryNull = sampleNulls(c.refuse)) != null)
                c.retryAt = now + 20;   // refused for objects the sample held (a path while it walked): an entity without them later
            s = new ES(e, c);
            if (c.refuse != null || e.getBlockX() < MIN_X || dev.lull.api.Compat.entitySleepExcluded(e)) s.st = St.NEVER;
            // the client: living entities follow the server's packets (interpolation, animation) - they stay awake there
            if (level.isClientSide && e instanceof net.minecraft.world.entity.LivingEntity) s.st = St.NEVER;
            else if (c.comp != null && (s.ctx = c.comp.resolveAll(e)) == null) s.st = St.NEVER;
            if (s.st != St.NEVER && c.barrier) {
                s.barrierOn = true;
                watchParts(s);
            }
            ((EntSleeperHolder) e).sleep$setEs(s);
        }
        if (s.st == St.ASLEEP && !s.woken && now >= s.wakeAt && s.drawWake) {
            // a draw takes the other branch this tick: run it for real; when it left the state the phase expects
            // (LookAtPlayerGoal's chance with no player around), look ahead again from the seed it left
            if (inlineTick(s, level)) {
                S().flipsPassed++;
                if (s.barrierOn) java.util.Arrays.fill(s.vdirty, 0);
                S().mode = "flip";
                long dd = lookAhead(s);
                long wake = Math.min(s.clockWakeAt, dd == Long.MAX_VALUE ? Long.MAX_VALUE : now + dd);
                s.drawWake = wake != s.clockWakeAt;
                s.wakeAt = wake;
                if (wake <= now + 1) {
                    wake(s, "deadline after a flip");
                    return;
                }
                if (wake != Long.MAX_VALUE) {
                    S().DEADLINES.add(wake, s.id);
                    S().SCHEDULED.put(s.id, s);
                }
                return;
            }
            wake(s, "flip changed: " + s.wakeReason);
            return;
        }
        if (s.st == St.ASLEEP && !s.woken && now < s.wakeAt && s.c.special(e.tickCount)) {
            // code under tickCount % N runs on this tick: the first such tick of each set of moduli in this sleep runs for
            // real and is checked; once one passed, the same code from the same state (the replay checks it every tick;
            // the clock conditions hold until the deadline) does the same - later ones are replayed
            int mask = s.c.specialMask(e.tickCount);
            if (!s.inlinePassed(mask)) {
                if (inlineTick(s, level)) {
                    S().inlined++;
                    if (s.barrierOn) java.util.Arrays.fill(s.vdirty, 0);   // the tick's writes were checked whole
                    s.addInlinePassed(mask);
                    S().mode = "inline";
                    return;
                }
                wake(s, s.wakeReason);   // the tick has run
                return;
            }
            S().inlineReplayed++;
        }
        if (s.st == St.ASLEEP) {
            if (!s.woken && now < s.wakeAt && replay(s)) {
                S().replayed++;
                S().mode = "replay";
                return;
            }
            wake(s, s.woken ? s.wakeReason : now >= s.wakeAt ? "deadline" : "state differs");
        }
        if (s.st == St.NEVER || e.tickCount < s.observeFrom || level.isClientSide && !CLIENT_ON) {
            if (TIMING && s.st != St.NEVER) S().mode = "backoff";
            e.tick();
            return;
        }
        if (PROBE && s.c.comp != null && s.k < 0 && !s.probeOk) {
            // not in a window: a plain tick with the primitives' hash before and after (~2 us, an observed tick costs
            // several ticks); a window starts once a tick left them as they were - the entity is at a fixed point
            EClass c = s.c;
            if (e.tickCount < s.probeFrom) {   // the last probes failed: plain ticks in between
                if (TIMING) S().mode = "probe wait";
                e.tick();
                return;
            }
            S().mode = "probe";
            int n = c.n, nc = c.clocks.length;
            if (s.rates == null) s.rates = new long[nc];
            if (S().PB.length < n) {
                S().PB = new long[n * 2];
                S().PA = new long[n * 2];
                S().PRB = new Object[n * 2];
            }
            long[] c0 = S().PROBE_CLOCKS.length >= nc ? S().PROBE_CLOCKS : (S().PROBE_CLOCKS = new long[nc * 2]);
            for (int j = 0; j < nc; j++) c0[j] = clockBits(s, j);
            boolean same;
            if (s.barrierOn) {   // the mirror: only the slots the tick wrote are read again
                sync(s, false);
                e.tick();
                same = !e.isRemoved() && !sync(s, false);
            } else {
                c.comp.both(e, S().PB, S().PRB);
                e.tick();
                same = !e.isRemoved();
                if (same) {
                    c.comp.both(e, S().PA, S().PRB);
                    boolean[] prim = c.prim, special = c.special;
                    for (int i = 0; i < n && same; i++) same = !prim[i] || special[i] || S().PA[i] == S().PB[i];
                }
            }
            if (same) {
                // the clocks' rates from this tick: a window only when the clock conditions leave a sleep worth it
                for (int j = 0; j < nc; j++) s.rates[j] = clockBits(s, j) - c0[j];
                same = deadline(s) > 2L * c.period + 1 + MOB_MIN_SLEEP;
            }
            if (same) {
                s.probeOk = true;
                s.probeFails = 0;
            } else {   // still moving: probe again after 1, 2, 4, 8 plain ticks
                s.probeFrom = e.tickCount + (1 << Math.min(3, s.probeFails++));
            }
            return;
        }
        s.probeOk = false;
        S().mode = "observe";
        observe(s, level);
    }


    /** -Dlab.entsleep.probe=false: mobs observe every tick outside a window (no cheap probe first) */
    private static final boolean PROBE = !"false".equals(System.getProperty("lab.entsleep.probe"));

    private static void observe(ES s, Level level) {
        Entity e = s.e;
        EClass c = s.c;
        int n = c.n;
        if (S().SB.length < n) {
            S().SB = new long[n * 2];
            S().SA = new long[n * 2];
            S().RB = new Object[n * 2];
            S().RA = new Object[n * 2];
        }
        long[] b = S().SB, a = S().SA;
        boolean cont = s.k >= 0 && e.tickCount == s.lastTick + 1;
        if (c.inline != null && !cont && !FIXED_STAT) {
            for (int t = 0; t <= c.period; t++)
                if (c.special(e.tickCount + t)) {   // the window would hold a tick that runs for real while asleep: later
                    if (TIMING) S().mode = "window later";
                    s.k = -1;
                    e.tick();
                    return;
                }
        }
        long tw = TIMING && !cont ? System.nanoTime() : 0;
        long tr = TIMING && !cont ? System.nanoTime() : 0;
        if (c.comp != null && !cont && (s.ctx = c.comp.resolveAll(e)) == null) {   // goals replaced: not this plan's mob now
            s.k = -1;
            e.tick();
            fail(s, "contexts changed");
            return;
        }
        if (TIMING && !cont) S().OT[20] += System.nanoTime() - tr;
        if (s.barrierOn && !cont) watchParts(s);
        if (TIMING && !cont) S().OT[21] += System.nanoTime() - tr;
        if (c.comp != null && !cont) {
            s.readsWeather = s.readsPlayers = false;
            s.attrObj = e instanceof net.minecraft.world.entity.LivingEntity le && le.getAttributes() instanceof MoveMemo.AttrEpoch ae ? ae : null;
            s.goal0 = MobSleep.fieldValue(e, "goalSelector") instanceof MobSleepHooks.GoalEpoch g0 ? g0 : null;
            s.goal1 = MobSleep.fieldValue(e, "targetSelector") instanceof MobSleepHooks.GoalEpoch g1 ? g1 : null;
            s.attrEpoch = attrEpoch(e);
            s.goalEpoch0 = goalEpoch(e, true);
            s.goalEpoch1 = goalEpoch(e, false);
        }
        if (TIMING && !cont) {
            S().OT[9] += System.nanoTime() - tw;
            S().OT[10]++;
        }
        if (TIMING && (S().OT[2] & 63) == 0) fpTimes(s);
        long o0 = TIMING ? System.nanoTime() : 0;
        if (c.comp != null && (s.content == null || s.content.length != n)) s.content = new boolean[n];
        if (s.barrierOn) {
            sync(s, !cont);
            System.arraycopy(s.M, 0, b, 0, n);
            System.arraycopy(s.MR, 0, S().RB, 0, n);
        } else if (c.comp != null) c.comp.snapObserved(e, b, S().RB, s.prevRefs, s.prevA, s.content, cont);
        else c.snap.snap(e, b);
        if (TIMING) S().OT[0] += System.nanoTime() - o0;
        S().obs = s;
        S().obsThread = Thread.currentThread();
        S().writes0 = BeTickStats.writesHere();
        S().seed0 = SleepEngine.seed(level);
        S().unob0 = S().unobservedE;
        S().readHash = 17;
        int L = c.period;
        if (!cont) {
            s.blockBox = null;
            s.entityBox = null;
            s.entityExposed = false;
            if (s.base == null || s.base.length != n) {
                s.base = new long[n];
                s.prevA = new long[n];
                s.baseRefs = new Object[n];
                s.rates = new long[c.clocks.length];
                s.diffs = new LongArrayList();
                s.diffRefs = new ArrayList<>();
                s.readHashAt = new long[L + 1];
            }
            if (s.barrierOn) System.arraycopy(S().RB, 0, s.baseRefs, 0, n);
            else c.snap.refs(e, s.baseRefs);   // the objects of the state before tick 0
        }
        Object rec = null;
        if (c.comp != null) {
            rec = get(c.randomField, e);
            MobSleep.TICK.clear();
            MobSleep.REC = rec;
        }
        long ti = TIMING ? System.nanoTime() : 0;
        try {
            e.tick();
        } finally {
            S().obs = null;
            MobSleep.REC = TRACE == e.getId() ? rec : null;
        }
        if (TIMING) {
            S().innerNs += System.nanoTime() - ti;
            S().innerN++;
        }
        S().observedTicks++;
        if (c.comp != null && (attrEpoch(e) != s.attrEpoch || goalEpoch(e, true) != s.goalEpoch0 || goalEpoch(e, false) != s.goalEpoch1)) {
            s.k = -1;
            fail(s, "attributes or goals changed");
            return;
        }
        long o1 = TIMING ? System.nanoTime() : 0;
        if (s.barrierOn) {
            sync(s, true);
            System.arraycopy(s.M, 0, a, 0, n);
            System.arraycopy(s.MR, 0, S().RA, 0, n);
        } else if (c.comp != null) c.comp.snapObserved(e, a, S().RA, S().RB, b, s.content, false);
        else c.snap.snap(e, a);
        if (TIMING) {
            S().OT[1] += System.nanoTime() - o1;
            S().OT[2]++;
        }
        boolean quiet = BeTickStats.writesHere() == S().writes0 && SleepEngine.seed(level) == S().seed0 && S().unobservedE == S().unob0 && !e.isRemoved();
        if (FIXED_STAT && c.comp != null) {
            fixedStat(s, b, a, quiet);
            s.k = -1;
            return;
        }
        if (!quiet) {
            s.k = -1;
            fail(s, c.comp == null ? "busy" : e.isRemoved() ? "busy: removed" : BeTickStats.writesHere() != S().writes0 ? "busy: world writes"
                    : SleepEngine.seed(level) != S().seed0 ? "busy: shared random" : "busy: unobserved reads");
            return;
        }
        if (!cont) {
            System.arraycopy(b, 0, s.base, 0, n);
            for (int j = 0; j < c.clocks.length; j++) s.rates[j] = a[c.clocks[j]] - b[c.clocks[j]];
            s.diffs.clear();
            s.diffRefs.clear();
            s.k = 0;
            s.startTick = e.tickCount;
            // a mob: the clocks' rates are known after the window's first tick - a sleep the clock conditions would end
            // within MOB_MIN_SLEEP ticks of its start does not pay for the window (half the windows ended so)
            if (c.comp != null && deadline(s) <= 2L * c.period + 1 + MOB_MIN_SLEEP) {
                s.k = -1;
                fail(s, "deadline too close (first tick)");
                return;
            }
        } else {
            s.k++;
            // clocks: no change outside the tick, the same change inside it
            for (int j = 0; j < c.clocks.length; j++) {
                int i = c.clocks[j];
                if (b[i] != s.prevA[i] || a[i] - b[i] != s.rates[j]) {
                    s.k = -1;
                    fail(s, c.comp != null ? "clock rate changed: " + slotName(c, i) : "clock rate changed");
                    return;
                }
            }
        }
        s.lastTick = e.tickCount;
        s.readHashAt[s.k] = S().readHash;
        if (c.comp != null) {
            if (s.drawLog == null || s.drawLog.length != L + 1) {
                s.drawLog = new LongArrayList[L + 1];
                for (int q = 0; q <= L; q++) s.drawLog[q] = new LongArrayList();
            }
            s.drawLog[s.k].clear();
            s.drawLog[s.k].addAll(MobSleep.TICK);
        }
        if (c.comp != null) {   // the last snapshot taken (with its objects): the next one reuses its fingerprints
            if (s.prevRefs == null || s.prevRefs.length != n) s.prevRefs = new Object[n];
            System.arraycopy(a, 0, s.prevA, 0, n);
            System.arraycopy(S().RA, 0, s.prevRefs, 0, n);
        }
        if (s.k == L) {
            // state before tick L must be the state before tick 0
            for (int i = 0; i < n; i++) {
                if (!c.special[i] && b[i] != s.base[i]) {
                    s.k = -1;
                    if (DEBUG && S().dbgP++ < DEBUG_N) System.out.println("[sleep/entsleep] not periodic: " + slotName(c, i) + " #" + e.getId() + " t=" + e.tickCount);
                    fail(s, c.comp != null ? "not periodic: " + slotName(c, i) : "not periodic");
                    return;
                }
            }
            if (s.readHashAt[L] != s.readHashAt[0]) {
                s.k = -1;
                fail(s, "reads not periodic");
                return;
            }
            long ts = TIMING ? System.nanoTime() : 0;
            trySleep(s, level);
            if (TIMING) {
                S().OT[7] += System.nanoTime() - ts;
                S().OT[8]++;
            }
            return;
        }
        // record differences from the base (before and after this tick)
        int kk = s.k;
        for (int i = 0; i < n; i++) {
            if (c.special[i]) continue;
            if (b[i] != s.base[i] && kk > 0) {
                s.diffs.add(((long) (kk * 2) << 16) | i);
                s.diffs.add(b[i]);
                s.diffRefs.add(null);
            }
            if (a[i] != s.base[i]) {
                s.diffs.add(((long) (kk * 2 + 1) << 16) | i);
                s.diffs.add(a[i]);
                s.diffRefs.add(c.f[i].getType().isPrimitive() ? null : c.ref(i, s));
            }
        }
        if (c.comp == null) System.arraycopy(a, 0, s.prevA, 0, n);
        if (s.diffs.size() > MAX_DIFFS) {
            s.k = -1;
            fail(s, "too many changes");
        }
    }

    private static void fail(ES s, String why) {
        if (++s.failStreak >= 4) {
            // back off: busy entities are observed less and less often
            // a mob's failures are mostly transient (its head turning after a look-around): it is soon idle again
            int wait = s.c.comp != null ? MOB_BACKOFF : Math.min(400, 20 << Math.min(4, s.failStreak - 4));
            s.observeFrom = s.e.tickCount + wait;
        }
        refuse(why);
    }

    private static void refuse(String why) {
        S().REFUSED.merge(why, 1L, Long::sum);
    }

    /** an assumed-zero field's value: a number 0 or a boolean false (1.20.1's isLazy / isInsidePortal are booleans) */
    private static boolean zero(Object v) {
        return v instanceof Boolean b ? !b : v instanceof Character ch ? ch == 0 : ((Number) v).longValue() == 0;
    }

    private static void trySleep(ES s, Level level) {
        Entity e = s.e;
        EClass c = s.c;
        int L = c.period;
        s.k = -1;
        if (NO_SLEEP_FOR.contains(e.getClass().getSimpleName())) { fail(s, "debug: never sleeps"); return; }
        for (int i : c.nulls) if (get(c.f[i], e) != null) { fail(s, "needs null " + c.f[i].getName()); return; }
        for (int i : c.zeros) if (!zero(get(c.f[i], e))) { fail(s, "needs zero " + c.f[i].getName()); return; }
        for (int i : c.entityRefs) if (get(c.f[i], e) != null) { fail(s, "holds entity " + c.f[i].getName()); return; }
        if (e.isPassenger() || e.isVehicle()) { fail(s, "riding"); return; }
        long drawDeadline = Long.MAX_VALUE;
        long y0 = TIMING ? System.nanoTime() : 0, y1 = y0, y2 = y0;
        if (c.comp != null) {
            if (c.assume == null) {
                String[] pp = new String[c.comp.parts.length];
                for (int k = 0; k < pp.length; k++) pp[k] = c.comp.parts[k].path;
                c.assume = MobSleep.compileAssumptions(c.mob, c.f, c.slotPath, pp);
            }
            String why = MobSleep.assumptionsFail(c.assume, e, S().SA, S().RA, s.ctx);   // the after-state observe has just taken
            if (why != null) { fail(s, why); return; }
            for (int i : c.ctxEntityRefs) {
                Object x = c.ref(i, s);
                if (x != null && x != e) { fail(s, "holds entity " + c.slotPath[i] + "." + c.f[i].getName()); return; }
            }
            if (TIMING) y1 = System.nanoTime();
            String dw = mobDraws(s);
            if (dw != null) { fail(s, dw); return; }
            if (TIMING) y2 = System.nanoTime();
            drawDeadline = lookAhead(s);
            if (TIMING) {
                long y3 = System.nanoTime();
                S().OT[11] += y1 - y0;
                S().OT[12] += y2 - y1;
                S().OT[13] += y3 - y2;
                S().OT[14]++;
            }
        }
        // the fields that vary within the period
        boolean[] inV = new boolean[c.n];
        int nv = 0;
        for (int d = 0; d < s.diffs.size(); d += 2) {
            int i = (int) (s.diffs.getLong(d) & 0xFFFF);
            if (!inV[i]) {
                inV[i] = true;
                nv++;
            }
        }
        int[] v = new int[nv];
        for (int i = 0, j = 0; i < c.n; i++) if (inV[i]) v[j++] = i;
        for (int i : v) {
            if (c.forbidV[i]) { fail(s, "varies " + slotName(c, i)); return; }
            Class<?> t = c.f[i].getType();
            if (!t.isPrimitive() && !valueType(t)) { fail(s, "varying reference " + slotName(c, i)); return; }
        }
        long[][] aV = new long[L][nv], bV = new long[L][nv];
        Object[][] aRef = new Object[L][];
        for (int p = 0; p < L; p++) {
            for (int j = 0; j < nv; j++) {
                aV[p][j] = s.base[v[j]];
                bV[p][j] = s.base[v[j]];
            }
        }
        for (int j = 0; j < nv; j++) {
            if (c.f[v[j]].getType().isPrimitive()) continue;
            for (int p = 0; p < L; p++) {
                if (aRef[p] == null) aRef[p] = new Object[nv];
                aRef[p][j] = s.baseRefs[v[j]];
            }
        }
        for (int d = 0, r = 0; d < s.diffs.size(); d += 2, r++) {
            long key = s.diffs.getLong(d);
            int i = (int) (key & 0xFFFF), ka = (int) (key >>> 16), kk = ka >> 1;
            int j = java.util.Arrays.binarySearch(v, i);
            if ((ka & 1) == 1) {
                aV[kk][j] = s.diffs.getLong(d + 1);
                if (aRef[kk] != null) aRef[kk][j] = s.diffRefs.get(r);
            } else {
                bV[kk][j] = s.diffs.getLong(d + 1);
            }
        }
        // a varying reference must be a value object (immutable, shared safely)
        for (int p = 0; p < L; p++) {
            if (aRef[p] == null) continue;
            for (Object o : aRef[p]) if (o != null && !valueType(o.getClass())) { fail(s, "varying mutable " + o.getClass().getSimpleName()); return; }
        }
        if (s.entityExposed && !neighboursAsleep(s)) { fail(s, "neighbours awake"); return; }
        for (int i : c.stillClocks) {
            for (int j = 0; j < c.clocks.length; j++) if (c.clocks[j] == i && s.rates[j] != 0) { fail(s, "clock under a modulus moves: " + slotName(c, i)); return; }
        }
        long z0 = TIMING ? System.nanoTime() : 0;
        long dClock = deadline(s);
        long d = Math.min(dClock, drawDeadline);
        if (d <= L + 1) { fail(s, drawDeadline <= L + 1 ? "draw flips too soon" : "deadline too close"); return; }
        // (a draw's deadline need not end the sleep: its tick runs for real and the sleep often goes on)
        if (c.comp != null && dClock <= L + 1 + MOB_MIN_SLEEP) { fail(s, "deadline too close to pay"); return; }
        s.clockWakeAt = dClock == Long.MAX_VALUE ? Long.MAX_VALUE : level.getGameTime() + dClock;
        s.drawWake = c.comp != null && drawDeadline < dClock;
        // a varying reference must not be replaced outside the tick (its expected identity is what the replay wrote)
        for (int j = 0; j < nv; j++) {
            if (c.f[v[j]].getType().isPrimitive()) continue;
            for (int p = 0; p < L; p++)
                if (bV[p][j] != aV[Math.floorMod(p - 1, L)][j] && !sameValue(bV[p][j], aV[Math.floorMod(p - 1, L)][j])) {
                    fail(s, "reference changed outside the tick: " + c.f[v[j]].getName());
                    return;
                }
        }
        // a mob: the state is the after-state observe has just taken (SA, its objects RA) - no new snapshot
        long[] cur = new long[c.n];
        if (c.comp != null) System.arraycopy(S().SA, 0, cur, 0, c.n);
        else c.snap.snap(e, cur);
        Object[] refs = new Object[c.n];
        if (c.comp != null) System.arraycopy(S().RA, 0, refs, 0, c.n);
        else c.snap.refs(e, refs);
        s.expR = new Object[c.refSlots.length];
        for (int k = 0; k < c.refSlots.length; k++) s.expR[k] = refs[c.refSlots[k]];
        long z1 = TIMING ? System.nanoTime() : 0;
        s.hashB = new long[L];
        for (int p = 0; p < L; p++) {
            for (int j = 0; j < nv; j++) if (c.prim[v[j]]) cur[v[j]] = bV[p][j];
            if (!s.barrierOn) s.hashB[p] = c.hashOf(cur);
        }
        if (s.barrierOn) {
            s.expPrim = cur;   // slots outside v: the after-state observe took
            java.util.Arrays.fill(s.vdirty, 0);
        }
        if (c.inline != null) {
            long[] now0 = new long[c.n];
            if (c.comp != null) System.arraycopy(S().SA, 0, now0, 0, c.n);
            else c.snap.snap(e, now0);
            s.afterExp = new long[L][];
            for (int p = 0; p < L; p++) {
                long[] x = now0.clone();
                for (int j = 0; j < nv; j++) x[v[j]] = aV[p][j];
                s.afterExp[p] = x;
            }
        }
        s.expClock = new long[c.clocks.length];
        for (int j = 0; j < c.clocks.length; j++) s.expClock[j] = c.snap.bits(e, c.clocks[j]);
        s.v = v;
        s.aV = aV;
        s.bV = bV;
        s.aRef = aRef;
        s.wakeAt = d == Long.MAX_VALUE ? Long.MAX_VALUE : level.getGameTime() + d;
        if (c.comp != null) s.expSeed = s.seedRef.get();
        s.woken = false;
        s.st = St.ASLEEP;
        s.inlineOkN = 0;
        s.failStreak = 0;
        s.diffs.clear();
        s.diffRefs.clear();
        S().entered++;
        long z2 = TIMING ? System.nanoTime() : 0;
        subscribe(s);
        if (TIMING) {
            long z3 = System.nanoTime();
            S().OT[15] += z0 - y0;   // up to the deadline (includes the timed parts above)
            S().OT[16] += z1 - z0;
            S().OT[17] += z2 - z1;
            S().OT[18] += z3 - z2;
            S().OT[19]++;
        }
        if (s.wakeAt != Long.MAX_VALUE) {
            S().DEADLINES.add(s.wakeAt, s.id);
            S().SCHEDULED.put(s.id, s);
        }
    }

    private static String slotName(EClass c, int i) {
        return c.slotPath == null || c.slotPath[i].isEmpty() ? c.f[i].getName() : c.slotPath[i] + "." + c.f[i].getName();
    }

    private static int attrEpoch(Entity e) {
        ES s = es(e);
        return s != null && s.attrObj != null ? s.attrObj.sleep$attrEpoch() : 0;
    }

    private static int goalEpoch(Entity e, boolean goals) {
        ES s = es(e);
        MobSleepHooks.GoalEpoch g = s == null ? null : goals ? s.goal0 : s.goal1;
        return g == null ? 0 : g.sleep$goalEpoch();
    }

    /** the collections assumed empty, by the objects resolved when it fell asleep */
    private static boolean stillEmpty(ES s) {
        for (Object o : s.emptyObjs)
            if (o instanceof java.util.Collection<?> col ? !col.isEmpty() : o instanceof java.util.Map<?, ?> m ? !m.isEmpty() : true) return false;
        return true;
    }

    /**
     * The window's draws: tick L draws as tick 0 did (same sites, kinds, bounds); each draw at a draw condition of the
     * plan whose other side is known; its outcome the same under both readings of a clock compared with it (before or
     * after the tick's change - which one the code reads is not known here). Fills the per-phase replay tables and the
     * outcomes the look-ahead keeps. Null if all is well, else why not.
     */
    private static String mobDraws(ES s) {
        EClass c = s.c;
        int L = c.period;
        if (!sameDraws(s.drawLog[0], s.drawLog[L])) return "draws not periodic";
        c.prepDraws();
        s.dKind = new int[L][];
        s.dBound = new int[L][];
        s.dCond = new int[L][];
        s.dOut = new boolean[L][];
        for (int p = 0; p < L; p++) {
            LongArrayList log = s.drawLog[p];
            int m = log.size() / 2;
            s.dKind[p] = new int[m];
            s.dBound[p] = new int[m];
            s.dCond[p] = new int[m];
            s.dOut[p] = new boolean[m];
            for (int j = 0; j < m; j++) {
                long key = log.getLong(2 * j);
                int ci = c.condOfSite(MobSleep.siteOf(key));
                if (ci == -1) return "draw outside the draw conditions: " + MobSleep.SITES.get(MobSleep.siteOf(key));
                if (ci == -2) return "two draw conditions on one line: " + MobSleep.SITES.get(MobSleep.siteOf(key));
                SleepAnalysis.DrawCond d = c.mob.draws().get(ci);
                if (d.other() == SleepAnalysis.DrawCond.UNKNOWN) return "draw compared with an unknown value: " + d.text();
                int kind = MobSleep.kindOf(key);
                double v = MobSleep.asNumber(kind, log.getLong(2 * j + 1));
                double[] o = otherSide(s, d, ci, p);
                if (o == null) return "draw condition's other side not found: " + d.text();
                boolean a = MobSleep.taken(d, v, o[0]), b = MobSleep.taken(d, v, o[1]);
                if (a != b) return "draw at its clock's edge";
                s.dKind[p][j] = kind;
                s.dBound[p][j] = MobSleep.boundOf(key);
                s.dCond[p][j] = ci;
                s.dOut[p][j] = a;
            }
        }
        s.random = (net.minecraft.util.RandomSource) get(c.randomField, s.e);
        s.seedRef = MobSleep.seedOf(s.random);
        if (s.seedRef == null) return "random of another kind";
        Object[] eo = new Object[c.emptyHolder.length];
        for (int q = 0; q < eo.length; q++) {
            Object h = c.holderOf(s, c.emptyHolder[q], c.emptyHolderPath[q]);
            Object v = h == null ? null : MobSleep.fieldValue(h, c.emptyName[q]);
            if (!(v instanceof java.util.Collection<?>) && !(v instanceof java.util.Map<?, ?>)) return "assumed-empty collection missing: " + c.emptyKeys.get(q);
            eo[q] = v;
        }
        s.emptyObjs = eo;
        s.clockBase = new long[c.clocks.length];
        for (int j = 0; j < c.clocks.length; j++) s.clockBase[j] = narrow(c.f[c.clocks[j]].getType(), s.base[c.clocks[j]]);
        s.dConst = new double[L][];
        s.dClock = new int[L][];
        for (int p = 0; p < L; p++) {
            int m = s.dKind[p].length;
            s.dConst[p] = new double[m];
            s.dClock[p] = new int[m];
            for (int j = 0; j < m; j++) {
                int ci = s.dCond[p][j];
                SleepAnalysis.DrawCond d = c.mob.draws().get(ci);
                s.dClock[p][j] = -1;
                if (d.other() == SleepAnalysis.DrawCond.CONST) {
                    s.dConst[p][j] = d.constant();
                } else if (d.other() == SleepAnalysis.DrawCond.FINAL) {
                    Object h = c.holderOf(s, c.condHolder[ci], c.condHolderPath[ci]);
                    Object fv = h == null ? null : get(d.field(), h);
                    if (!(fv instanceof Number nb)) return "draw condition's field missing: " + d.text();
                    s.dConst[p][j] = nb.doubleValue();
                } else {
                    int jj = c.condClock[ci];
                    if (jj < 0) return "draw condition's clock not found: " + d.text();
                    s.dClock[p][j] = jj;
                    s.dConst[p][j] = d.constant();
                }
            }
        }
        return null;
    }

    private static boolean sameDraws(LongArrayList x, LongArrayList y) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i += 2) if (x.getLong(i) != y.getLong(i)) return false;
        return true;
    }

    /**
     * The other side of a draw condition at window tick k (t ticks after the window's start): a constant, a final
     * field, or a clock + constant - both readings of the clock: its value at the tick's start and after its change.
     */
    private static double[] otherSide(ES s, SleepAnalysis.DrawCond d, int ci, long k) {
        EClass c = s.c;
        if (d.other() == SleepAnalysis.DrawCond.CONST) return new double[] {d.constant(), d.constant()};
        if (d.other() == SleepAnalysis.DrawCond.FINAL) {
            Object h = c.holderOf(s, c.condHolder[ci], c.condHolderPath[ci]);
            Object v = h == null ? null : get(d.field(), h);
            if (!(v instanceof Number n)) return null;
            return new double[] {n.doubleValue(), n.doubleValue()};
        }
        int j = c.condClock[ci];
        if (j < 0) return null;
        int slot = c.clocks[j];
        long r = s.rates[j];
        long start = narrow(c.f[slot].getType(), s.base[slot] + r * k);
        return new double[] {start + d.constant(), narrow(c.f[slot].getType(), start + r) + d.constant()};
    }

    /**
     * Before a replayed tick: the phase's draws on a copy of the seed must take the observed branches (the look-ahead
     * said so; this keeps the replay honest whatever the look-ahead got wrong). Null if they do.
     */
    private static String drawCheck(ES s, int p, long el) {
        EClass c = s.c;
        int[] dk = s.dKind[p];
        if (dk.length == 0) return null;
        MobSleep.Lcg g = S().SCRATCH;
        g.seed = s.expSeed;
        for (int j = 0; j < dk.length; j++) {
            SleepAnalysis.DrawCond d = c.mob.draws().get(s.dCond[p][j]);
            double v = MobSleep.asNumber(dk[j], g.draw(dk[j], s.dBound[p][j]));
            if (MobSleep.taken(d, v, other(s, p, j, el, 0)) != s.dOut[p][j] || MobSleep.taken(d, v, other(s, p, j, el, 1)) != s.dOut[p][j]) {
                S().drawCheckFails++;
                return "draw outcome: " + d.text();
            }
        }
        return null;
    }


    /** The look-ahead horizon: past it the entity wakes to look again. */
    private static final int HORIZON = Integer.getInteger("lab.entsleep.drawHorizon", 4000);

    /** the other side of draw j of phase p at window tick el: index 0 the clock at the tick's start, 1 after its change */
    private static double other(ES s, int p, int j, long el, int which) {
        int jj = s.dClock[p][j];
        if (jj < 0) return s.dConst[p][j];
        EClass c = s.c;
        Class<?> t = c.f[c.clocks[jj]].getType();
        long start = narrow(t, s.clockBase[jj] + s.rates[jj] * el);
        return (which == 0 ? start : narrow(t, start + s.rates[jj])) + s.dConst[p][j];
    }


    /**
     * Ticks (in deadline() units) until a draw of the replayed ticks would take another branch than the observed one:
     * the draws simulated on a copy of the random's seed, the clocks advanced by their rates.
     */
    private static long lookAhead(ES s) {
        EClass c = s.c;
        int L = c.period;
        MobSleep.Lcg g = S().SCRATCH;
        g.seed = s.seedRef.get();
        long el0 = (long) s.e.tickCount + 1 - s.startTick;   // the first replayed tick, from the window's start
        for (long t = 0; t < HORIZON; t++) {
            long el = el0 + t;
            int p = (int) Math.floorMod(el, (long) L);
            int[] dk = s.dKind[p];
            for (int j = 0; j < dk.length; j++) {
                SleepAnalysis.DrawCond d = c.mob.draws().get(s.dCond[p][j]);
                double v = MobSleep.asNumber(dk[j], g.draw(dk[j], s.dBound[p][j]));
                if (MobSleep.taken(d, v, other(s, p, j, el, 0)) != s.dOut[p][j] || MobSleep.taken(d, v, other(s, p, j, el, 1)) != s.dOut[p][j]) return t + 1;
            }
        }
        return HORIZON;
    }

    /** two objects of an immutable value type (Vec3, AABB, BlockPos, boxed numbers, strings, enums) with equal values */
    private static boolean sameValue(Object a, Object b) {
        return a != null && b != null && a.getClass() == b.getClass() && valueType(a.getClass()) && a.equals(b);
    }

    /**
     * The compiled identity check failed: the same state by value? A reference field of an immutable value type replaced by
     * an equal object (the client: the server's motion packet sets the velocity the entity already had) is the same
     * state - vanilla never compares these by identity. The new object becomes the expected one (the fast path again).
     */
    private static boolean sameByValue(ES s, Entity e) {
        EClass c = s.c;
        int[] rs = c.refSlots;
        Object[] exp = s.expR;
        for (int k = 0; k < rs.length; k++) {
            Object cur = get(c.f[rs[k]], e);
            if (cur == exp[k]) continue;
            if (!sameValue(cur, exp[k])) return false;
            exp[k] = cur;
        }
        return true;
    }

    private static boolean valueType(Class<?> t) {
        return t == Vec3.class || t == AABB.class || t == BlockPos.class || t == Boolean.class || t == Integer.class || t == Long.class
                || t == Float.class || t == Double.class || t == Short.class || t == Byte.class || t == String.class || t.isEnum()
                || t == java.util.Optional.class || t == Object.class && false;
    }

    /** Every entity in the boxes whose objects the tick received must be asleep (its state can then only change with an event). */
    private static boolean neighboursAsleep(ES s) {
        if (s.entityBox == null) return true;
        for (Entity x : s.e.level().getEntities(s.e, s.entityBox)) {
            ES xs = es(x);
            if (xs == null || xs.st != St.ASLEEP) return false;
        }
        return true;
    }

    /** Ticks until a clock condition can change its outcome (Long.MAX_VALUE: never while the rates hold). */
    private static long deadline(ES s) {
        EClass c = s.c;
        Entity e = s.e;
        long best = Long.MAX_VALUE;
        List<SleepAnalysis.Cond> cs = c.plan.conds();
        for (int cj = 0; cj < cs.size(); cj++) {
            SleepAnalysis.Cond cond = cs.get(cj);
            if (cond.clockMod()) continue;   // inside the period
            if (cond.clock() == null) return 0;   // game-time modulo: not aligned with the entity's phase
            long cv, r;
            int ci = c.condSlot != null ? c.condSlot[cj] : index(c, cond.clock());
            if (ci < 0) return 0;
            cv = c.comp != null ? narrow(c.f[ci].getType(), c.snap.bits(e, ci)) : longValue(c.f[ci], e);
            r = ci == c.tickCount ? 1 : rateOf(s, ci);
            if (cond.band()) {   // a value of the clock varies inside (lo, hi): no sleep there, a deadline before entering
                long lo = cond.thresholdConst(), hi = cond.bandHi();
                if (r == 0) continue;   // a clock standing still: the value is constant wherever it stands
                if (cv > lo && cv < hi) return 0;
                long w0 = cv - r * (c.period + 1);   // the clock when the window began
                if (Math.max(w0, cv) > lo && Math.min(w0, cv) < hi) return 0;   // the window passed through the band
                if (r > 0 && cv <= lo && lo != Long.MAX_VALUE) best = Math.min(best, Math.max(0, (lo + 1 - cv) / r - 1));
                if (r < 0 && cv >= hi && hi != Long.MIN_VALUE) best = Math.min(best, Math.max(0, (cv - (hi - 1)) / -r - 1));
                continue;
            }
            if (cond.age()) {   // the stamp's age: game time - stamp, one more each tick
                if (r != 0) return 0;
                cv = e.level().getGameTime() - cv;
                r = 1;
            }
            if (cond.peer()) {
                if (!s.entityExposed) continue;   // no neighbour object reached the tick in the observed period
                long dd = peerDeadline(s, ci, cv, r);
                best = Math.min(best, dd);
                continue;
            }
            if (r == 0) continue;
            if (TRACE == e.getId()) System.out.println("[sleep/trace] cond " + cond.text() + " slot " + slotName(c, ci) + " cv=" + cv + " r=" + r + " best=" + best);
            long t = cond.thresholdConst();
            if (cond.thresholdField() != null) {
                int ti = c.condThreshold != null ? c.condThreshold[cj] : index(c, cond.thresholdField());
                if (ti < 0) return 0;
                t += c.comp != null ? narrow(c.f[ti].getType(), c.snap.bits(e, ti)) : longValue(c.f[ti], e);
            }
            // the window must not have straddled the threshold: a test evaluated in it (on some phase only - a goal's
            // canContinueToUse every second tick) may have seen the other side than the clock is on now
            long w0 = cv - r * (c.period + 1);
            if (Math.min(w0, cv) <= t + 1 && Math.max(w0, cv) >= t - 1) return 0;
            long dist = r > 0 ? t - cv : cv - t;
            if (dist < -1) continue;   // passed and moving away
            best = Math.min(best, Math.max(0, dist / Math.abs(r) - 1));
        }
        return best;
    }

    /** this.clock ? other.clock for other entities of the same class in the read boxes: when their order can flip. */
    private static long peerDeadline(ES s, int ci, long cv, long r) {
        if (s.entityBox == null) return Long.MAX_VALUE;
        long best = Long.MAX_VALUE;
        for (Entity x : s.e.level().getEntities(s.e, s.entityBox)) {
            if (x.getClass() != s.e.getClass()) continue;
            ES xs = es(x);
            if (xs == null || xs.st != St.ASLEEP) return 0;
            long xv = longValue(s.c.f[ci], x), xr = ci == s.c.tickCount ? 1 : rateOf(xs, ci);
            long dr = r - xr, diff = cv - xv;
            if (dr == 0) continue;
            long t = -diff / dr;   // ticks until equal
            if (t < -1) continue;
            best = Math.min(best, Math.max(0, t - 1));
        }
        return best;
    }

    private static long rateOf(ES s, int fieldIndex) {
        int[] cl = s.c.clocks;
        for (int j = 0; j < cl.length; j++) if (cl[j] == fieldIndex) return s.rates[j];
        return 0;
    }

    private static int index(EClass c, Field f) {
        for (int i = 0; i < c.n; i++) if (c.f[i].equals(f)) return i;
        return -1;
    }

    /** One skipped tick: check the state is the expected one for this phase, write the tick's results, advance clocks. */
    /** clock j of a mob's composite state, in the snapshot encoding (EClass.clockOff: direct; else the snapper) */
    private static long clockBits(ES s, int j) {
        EClass c = s.c;
        if (c.clockOff == null) return c.snap.bits(s.e, c.clocks[j]);
        Object o = s.ctx[c.clockPart[j]];
        long off = c.clockOff[j];
        sun.misc.Unsafe u = SnapCompiler.U;
        return switch (c.clockKind[j]) {
            case 0 -> u.getInt(o, off);
            case 1 -> u.getLong(o, off);
            case 2 -> u.getShort(o, off);
            default -> u.getByte(o, off);
        };
    }

    private static void clockPut(ES s, int j, long x) {
        EClass c = s.c;
        if (c.clockOff == null) {
            c.snap.put(s.e, c.clocks[j], x, null);
            return;
        }
        Object o = s.ctx[c.clockPart[j]];
        long off = c.clockOff[j];
        sun.misc.Unsafe u = SnapCompiler.U;
        switch (c.clockKind[j]) {
            case 0 -> u.putInt(o, off, (int) x);
            case 1 -> u.putLong(o, off, x);
            case 2 -> u.putShort(o, off, (short) x);
            default -> u.putByte(o, off, (byte) x);
        }
    }

    private static boolean replay(ES s) {
        Entity e = s.e;
        EClass c = s.c;
        int L = c.period;
        long el = (long) e.tickCount - s.startTick;
        int p = (int) Math.floorMod(el, (long) L);
        int[] v = s.v;
        boolean[] prim = c.prim;
        SnapCompiler.Snapper sn = c.snap;
        int[] cl = c.clocks;
        long q0 = TIMING ? System.nanoTime() : 0;
        if (VERIFY && s.barrierOn) {
            // the write barrier: only the slots written since the last replay (the level's old position and tick count
            // every tick; anything else wakes); the replay's own writes go around it
            String bad = dirtyCheck(s, p);
            if (bad != null) return verifyFail(s, bad);
        } else if (VERIFY) {
            // every field: primitives by a hash of their values, references by identity (the objects left by the last
            // replay); synched data changes arrive through its set() hook. Clocks separately.
            if (sn.hashPrims(e) != s.hashB[p]) return verifyFail(s, firstDiff(s, p));
            long qa = TIMING ? System.nanoTime() : 0;
            if (!sn.sameRefs(e, s.expR) && (c.comp != null || !sameByValue(s, e))) return verifyFail(s, firstDiff(s, p));
            long qb = TIMING ? System.nanoTime() : 0;
            for (int j = 0; j < cl.length; j++) if (clockBits(s, j) != s.expClock[j]) return verifyFail(s, "clock " + slotName(c, cl[j]));
            if (TIMING) {
                S().RT[7] += qa - q0;
                S().RT[8] += qb - qa;
                long qc = System.nanoTime();
                S().RT[10] += sn.hashPrims(e) == 0 ? 1 : 0;
                S().RT[9] += System.nanoTime() - qc;   // the same hash again, its data now in the cache
                q0 += System.nanoTime() - qc;
            }
        }
        long q1 = TIMING ? System.nanoTime() : 0;
        if (c.comp != null) {
            if (!stillEmpty(s)) return verifyFail(s, "a collection assumed empty");
            if (attrEpoch(e) != s.attrEpoch || goalEpoch(e, true) != s.goalEpoch0 || goalEpoch(e, false) != s.goalEpoch1) return verifyFail(s, "attributes or goals");
            if (s.seedRef.get() != s.expSeed) return verifyFail(s, "random drawn outside the tick");
            String dc = drawCheck(s, p, el);
            if (dc != null) return verifyFail(s, dc);
            if (s.readsWeather && !noWeather(e.level())) return verifyFail(s, "weather");
            if (s.readsPlayers && !noPlayers(e.level())) return verifyFail(s, "players");
        }
        long q2 = TIMING ? System.nanoTime() : 0;
        long[] ap = s.aV[p];
        Object[] ar = s.aRef[p];
        for (int j = 0; j < v.length; j++) {
            int i = v[j];
            if (prim[i]) {
                sn.put(e, i, ap[j], null);
            } else {
                sn.put(e, i, 0, ar[j]);
                int k = c.refOrd[i];
                if (k >= 0) s.expR[k] = ar[j];
            }
        }
        long[] rates = s.rates;
        for (int j = 0; j < cl.length; j++) {
            long r = rates[j];
            if (r == 0) continue;
            long x = narrow(c.f[cl[j]].getType(), s.expClock[j] + r);
            s.expClock[j] = x;
            clockPut(s, j, x);
        }
        long q3 = TIMING ? System.nanoTime() : 0;
        if (c.comp != null) {
            int[] dk = s.dKind[p], db = s.dBound[p];
            for (int j = 0; j < dk.length; j++) MobSleep.replay(s.random, dk[j], db[j]);
            s.expSeed = s.seedRef.get();
        }
        if (TIMING) {
            long q4 = System.nanoTime();
            S().RT[0] += q1 - q0;
            S().RT[1] += q2 - q1;
            S().RT[2] += q3 - q2;
            S().RT[3] += q4 - q3;
            S().RT[4]++;
            S().RT[5] += v.length;
            S().RT[6] += c.comp != null ? s.dKind[p].length : 0;
        }
        return true;
    }

    private static final int MOB_BACKOFF = Integer.getInteger("lab.entsleep.mobBackoff", 20);
    /** a mob's shortest sleep worth its observation window (~15 plain ticks of work on the N100) */
    private static final int MOB_MIN_SLEEP = Integer.getInteger("lab.entsleep.mobMinSleep", 16);

    /**
     * A tick that runs for real while asleep (tickCount % N == r of the plan: code the window did not see). Checked
     * like a replayed tick before; watched like an observed tick while it runs (no world write, no shared random, no
     * unobserved read, no read outside the subscribed boxes); after it the whole state must be the phase's expected
     * after-state, the clocks advanced by their rates and the random advanced by exactly the phase's draws. Then the
     * replay goes on, with the objects the tick left as the expected references. False: woken (s.wakeReason).
     */
    private static boolean inlineTick(ES s, Level level) {
        Entity e = s.e;
        EClass c = s.c;
        int L = c.period;
        int p = (int) Math.floorMod((long) e.tickCount - s.startTick, (long) L);
        SnapCompiler.Snapper sn = c.snap;
        int[] cl = c.clocks;
        if (s.barrierOn) {   // the slots written since the last replay (clocks are written only by it and the tick)
            String bad = dirtyCheck(s, p);
            if (bad != null) return inlineFail(s, "changed: " + bad);
        } else {
            if (sn.hashPrims(e) != s.hashB[p] || !sn.sameRefs(e, s.expR)) return inlineFail(s, "changed: " + firstDiff(s, p));
            for (int j = 0; j < cl.length; j++) if (sn.bits(e, cl[j]) != s.expClock[j]) return inlineFail(s, "changed: clock " + slotName(c, cl[j]));
        }
        if (!stillEmpty(s) || attrEpoch(e) != s.attrEpoch || goalEpoch(e, true) != s.goalEpoch0 || goalEpoch(e, false) != s.goalEpoch1
                || s.seedRef.get() != s.expSeed || s.readsWeather && !noWeather(level) || s.readsPlayers && !noPlayers(level))
            return inlineFail(s, "changed before an inline tick");
        AABB bb0 = s.blockBox, eb0 = s.entityBox;
        long w = BeTickStats.writesHere(), sd = SleepEngine.seed(level), u = S().unobservedE;
        S().obs = s;
        S().obsThread = Thread.currentThread();
        S().readHash = 17;
        try {
            e.tick();
        } finally {
            S().obs = null;
        }
        if (BeTickStats.writesHere() != w || SleepEngine.seed(level) != sd || S().unobservedE != u || e.isRemoved()) return inlineFail(s, "inline tick busy");
        if (!java.util.Objects.equals(bb0, s.blockBox) || !java.util.Objects.equals(eb0, s.entityBox)) {
            s.blockBox = bb0;
            s.entityBox = eb0;
            return inlineFail(s, "inline tick read elsewhere");
        }
        if (s.barrierOn) return inlineAfter(s, p);
        // the primitives it left: those the next tick expects (its before-state is this tick's after-state)
        if (sn.hashPrims(e) != s.hashB[(p + 1) % L]) {
            long[] now = S().SA.length >= c.n ? S().SA : (S().SA = new long[c.n * 2]);
            sn.snap(e, now);
            long[] exp = s.afterExp[p];
            for (int i = 0; i < c.n; i++) if (!c.special[i] && c.prim[i] && now[i] != exp[i]) return inlineFail(s, "inline tick left " + slotName(c, i));
            return inlineFail(s, "inline tick left a primitive");
        }
        long[] rates = s.rates;
        for (int j = 0; j < cl.length; j++) {
            long x = narrow(c.f[cl[j]].getType(), s.expClock[j] + rates[j]);
            if (sn.bits(e, cl[j]) != x) return inlineFail(s, "inline tick moved clock " + slotName(c, cl[j]));
            s.expClock[j] = x;
        }
        MobSleep.Lcg g = new MobSleep.Lcg(s.expSeed);
        int[] dk = s.dKind[p], db = s.dBound[p];
        for (int j = 0; j < dk.length; j++) g.draw(dk[j], db[j]);
        if (s.seedRef.get() != g.seed) return inlineFail(s, "inline tick drew otherwise");
        s.expSeed = g.seed;
        // references: the same objects, or new ones equal by value to what the phase leaves (then adopted)
        Object[] refs = S().REFS.length >= c.n ? S().REFS : (S().REFS = new Object[c.n * 2]);
        sn.refs(e, refs);
        long[] exp = s.afterExp[p];
        for (int k = 0; k < c.refSlots.length; k++) {
            int i = c.refSlots[k];
            Object o = refs[i];
            if (o == s.expR[k]) continue;
            if (EntityFp.fp(o) != exp[i]) return inlineFail(s, "inline tick left " + slotName(c, i));
            s.expR[k] = o;
        }
        return true;
    }

    /**
     * After an inline tick, with the write barrier: the slots it wrote (vdirty), the phase's varying slots and the
     * clocks against the after-state the phase expects; the random by the phase's draws. The rest was not written.
     */
    private static boolean inlineAfter(ES s, int p) {
        Entity e = s.e;
        EClass c = s.c;
        long[] exp = s.afterExp[p];
        Object[] o = s.ctx;
        long[] d = s.vdirty;
        for (int j : s.v) markDirty(d, j);
        for (int w = 0; w < d.length; w++) {
            long bits = d[w];
            if (bits == 0) continue;
            d[w] = 0;
            while (bits != 0) {
                int i = (w << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                if (c.special[i]) continue;   // tick count, random, clocks: below
                if (c.prim[i]) {
                    if (c.snap.bits(e, i) != exp[i]) return inlineFail(s, "inline tick left " + slotName(c, i));
                } else {
                    int k = c.refOrd[i];
                    if (k < 0) continue;
                    Object x = SnapCompiler.U.getObject(o[c.comp.partOf[i]], c.slotOff[i]);
                    if (x == s.expR[k]) continue;
                    if (EntityFp.fp(x) != exp[i]) return inlineFail(s, "inline tick left " + slotName(c, i));
                    s.expR[k] = x;
                }
            }
        }
        int[] cl = c.clocks;
        long[] rates = s.rates;
        for (int j = 0; j < cl.length; j++) {
            long x = narrow(c.f[cl[j]].getType(), s.expClock[j] + rates[j]);
            if (clockBits(s, j) != x) return inlineFail(s, "inline tick moved clock " + slotName(c, cl[j]));
            s.expClock[j] = x;
        }
        MobSleep.Lcg g = new MobSleep.Lcg(s.expSeed);
        int[] dk = s.dKind[p], db = s.dBound[p];
        for (int j = 0; j < dk.length; j++) g.draw(dk[j], db[j]);
        if (s.seedRef.get() != g.seed) return inlineFail(s, "inline tick drew otherwise");
        s.expSeed = g.seed;
        return true;
    }

    private static boolean inlineFail(ES s, String why) {
        S().inlineFails++;
        s.wakeReason = why;
        s.woken = true;
        return false;
    }

    /** Which field broke the replay check (diagnostics, slow). */
    private static String firstDiff(ES s, int p) {
        EClass c = s.c;
        for (int k = 0; k < c.refSlots.length; k++) if (c.ref(c.refSlots[k], s) != s.expR[k]) return slotName(c, c.refSlots[k]);
        long[] one = new long[c.n];
        c.snap.snap(s.e, one);
        // primitives: which one differs from the phase's expected values
        long[] exp = one.clone();
        for (int i = 0; i < c.n; i++) if (c.prim[i] && !c.special[i]) exp[i] = s.base[i];
        for (int j = 0; j < s.v.length; j++) if (c.prim[s.v[j]]) exp[s.v[j]] = s.bV[p][j];
        for (int i = 0; i < c.n; i++) if (c.prim[i] && !c.special[i] && one[i] != exp[i]) return slotName(c, i);
        return "primitive hash";
    }

    private static boolean verifyFail(ES s, String field) {
        S().verifyFails++;
        if (DEBUG && S().dbg++ < 12) {
            long el = (long) s.e.tickCount - s.startTick;
            StringBuilder sb = new StringBuilder("[sleep/entsleep] verify fail " + field + " #" + s.e.getId() + " el=" + el + " phase=" + Math.floorMod(el, (long) s.c.period) + " now=");
            for (int j = 0; j < s.v.length; j++) sb.append(slotName(s.c, s.v[j])).append(' ');
            sb.append(" table:");
            for (int p = 0; p < Math.min(8, s.c.period); p++) {
                sb.append(" [").append(p).append(']');
                for (int j = 0; j < s.v.length; j++) sb.append(' ').append(Long.toHexString(s.bV[p][j] & 0xFFFF)).append('>').append(s.aRef[p] != null && s.aRef[p][j] != null ? s.aRef[p][j] : Long.toHexString(s.aV[p][j] & 0xFFFF));
            }
            System.out.println(sb);
        }
        s.wakeReason = "changed: " + field;
        s.woken = true;
        return false;
    }

    private static void wake(ES s, String reason) {
        if (s.barrierOn) {
            if (s.v != null) for (int i : s.v) markDirty(s.mdirty, i);
            for (int i : s.c.clocks) markDirty(s.mdirty, i);
        }
        s.st = St.OBSERVE;
        s.k = -1;
        s.woken = false;
        s.v = null;
        s.aV = s.bV = null;
        s.aRef = null;
        s.subEpoch++;
        S().SCHEDULED.remove(s.id);
        S().WAKES.merge(reason.startsWith("changed: ") ? reason : reason, 1L, Long::sum);
        onEntity(s.e, "neighbour woke");   // readers of this entity may depend on it being asleep
    }

    private static void request(ES s, String reason) {
        if (s.st == St.ASLEEP && !s.woken) {
            s.woken = true;
            s.wakeReason = reason;
        }
    }

    // ------------------------------------------------------------------ deadlines


    private static void poll(long now) {
        S().lastPoll = now;
        if (S().DEADLINES.size() == 0) return;
        for (long id; (id = S().DEADLINES.takeDue(now)) != LabScheduler.NONE; ) {
            ES s = S().SCHEDULED.get(id);
            if (s == null) continue;
            if (s.st != St.ASLEEP || s.e.isRemoved()) {
                S().SCHEDULED.remove(id);
            } else if (s.wakeAt <= now && !s.drawWake) {
                S().SCHEDULED.remove(id);
                S().deadlineWakes++;
                request(s, "deadline");
            }
        }
        S().DEADLINES.endTick(now);
    }

    // ------------------------------------------------------------------ reads (observation) and subscriptions

    static void readPos(char kind, long p) {
        ES s = S().obs;
        int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
        s.blockBox = union(s.blockBox, x, y, z, x + 1, y + 1, z + 1);
        S().readHash = S().readHash * 1_000_003L + kind * 31L + p;
    }

    static void readBox(char kind, AABB box) {
        ES s = S().obs;
        s.entityBox = s.entityBox == null ? box : s.entityBox.minmax(box);
        if (kind != 'C') s.entityExposed = true;
        S().readHash = S().readHash * 1_000_003L + kind * 31L + Double.doubleToLongBits(box.minX) * 7 + Double.doubleToLongBits(box.minY) * 13
                + Double.doubleToLongBits(box.minZ) * 17 + Double.doubleToLongBits(box.maxX + box.maxY + box.maxZ);
    }

    /** Block collision / support queries: every block within the box (and one around it) is read. */
    static void readRegion(AABB box) {
        ES s = S().obs;
        s.blockBox = union(s.blockBox, Math.floor(box.minX) - 1, Math.floor(box.minY) - 1, Math.floor(box.minZ) - 1,
                Math.floor(box.maxX) + 2, Math.floor(box.maxY) + 2, Math.floor(box.maxZ) + 2);
        S().readHash = S().readHash * 1_000_003L + 'R' * 31L + Double.doubleToLongBits(box.minX) * 7 + Double.doubleToLongBits(box.minY) * 13
                + Double.doubleToLongBits(box.minZ) * 17 + Double.doubleToLongBits(box.maxX + box.maxY + box.maxZ);
    }

    static void unobservedRead() {
        S().unobservedE++;
        if (UNOBS_WHY) {
            String w = java.lang.StackWalker.getInstance().walk(fs -> fs.skip(3).limit(4).map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                    .reduce((x, y) -> x + " < " + y).orElse("?"));
            S().REFUSED.merge("unobserved via " + w, 1L, Long::sum);
        }
    }

    private static final Field RAIN = MobSleep.field(Level.class, "rainLevel"), THUNDER = MobSleep.field(Level.class, "thunderLevel");
    private static final Field PLAYERS = MobSleep.field(net.minecraft.server.level.ServerLevel.class, "players");

    private static boolean noWeather(Level level) {
        try {
            return RAIN != null && THUNDER != null && RAIN.getFloat(level) == 0 && THUNDER.getFloat(level) == 0;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static boolean noPlayers(Level level) {
        try {
            return PLAYERS != null && ((java.util.List<?>) PLAYERS.get(level)).isEmpty();
        } catch (IllegalAccessException | ClassCastException e) {
            return false;
        }
    }

    /** a mob being observed read the weather: an observed read while there is none (true), else unobserved */
    static boolean weatherRead(Level level) {
        ES s = S().obs;
        if (s.c.comp == null || !noWeather(level)) return false;
        s.readsWeather = true;
        return true;
    }

    /** a mob being observed read the player list: observed while the level has no players */
    static boolean playersRead(java.util.List<?> players) {
        ES s = S().obs;
        if (s.c.comp == null || !players.isEmpty()) return false;
        s.readsPlayers = true;
        return true;
    }

    private static final boolean UNOBS_WHY = Boolean.getBoolean("lab.entsleep.unobsWhy");

    private static AABB union(AABB b, double x0, double y0, double z0, double x1, double y1, double z1) {
        if (b == null) return new AABB(x0, y0, z0, x1, y1, z1);
        if (x0 >= b.minX && y0 >= b.minY && z0 >= b.minZ && x1 <= b.maxX && y1 <= b.maxY && z1 <= b.maxZ) return b;
        return new AABB(Math.min(x0, b.minX), Math.min(y0, b.minY), Math.min(z0, b.minZ), Math.max(x1, b.maxX), Math.max(y1, b.maxY), Math.max(z1, b.maxZ));
    }

    /** Subscribers per section, with lazy removal (an entry is live while its sleeper is asleep with the same epoch). */
    /**
     * A section's subscribers, with each one's box in a flat array: an event tests the boxes first and touches only the
     * sleepers it hits (an entity moving among a hundred sleepers cost a cache miss per sleeper, villagers ~8 us a tick
     * on the N100); {@code u} is the union of the boxes, for a whole list missed at once.
     */
    static final class SubList {
        ES[] es = new ES[4];
        int[] ep = new int[4];
        double[] bx = new double[24];
        double u0 = Double.MAX_VALUE, u1 = Double.MAX_VALUE, u2 = Double.MAX_VALUE, u3 = -Double.MAX_VALUE, u4 = -Double.MAX_VALUE, u5 = -Double.MAX_VALUE;
        int n;

        void add(ES s, AABB b) {
            if (n == es.length) {
                compact();
                if (n == es.length) {
                    es = java.util.Arrays.copyOf(es, n * 2);
                    ep = java.util.Arrays.copyOf(ep, n * 2);
                    bx = java.util.Arrays.copyOf(bx, n * 12);
                }
            }
            es[n] = s;
            ep[n] = s.subEpoch;
            int o = n * 6;
            bx[o] = b.minX;
            bx[o + 1] = b.minY;
            bx[o + 2] = b.minZ;
            bx[o + 3] = b.maxX;
            bx[o + 4] = b.maxY;
            bx[o + 5] = b.maxZ;
            n++;
            widen(o);
        }

        private void widen(int o) {
            u0 = Math.min(u0, bx[o]);
            u1 = Math.min(u1, bx[o + 1]);
            u2 = Math.min(u2, bx[o + 2]);
            u3 = Math.max(u3, bx[o + 3]);
            u4 = Math.max(u4, bx[o + 4]);
            u5 = Math.max(u5, bx[o + 5]);
        }

        void compact() {
            int m = 0;
            u0 = u1 = u2 = Double.MAX_VALUE;
            u3 = u4 = u5 = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                ES s = es[i];
                if (s.st == St.ASLEEP && ep[i] == s.subEpoch && !s.e.isRemoved()) {
                    es[m] = s;
                    ep[m] = ep[i];
                    System.arraycopy(bx, i * 6, bx, m * 6, 6);
                    widen(m * 6);
                    m++;
                }
            }
            for (int i = m; i < n; i++) es[i] = null;
            n = m;
        }

        /** whether box i holds the point (containment, like AABB's bounds inclusive) */
        boolean holds(int i, double x, double y, double z) {
            int o = i * 6;
            return x >= bx[o] && x <= bx[o + 3] && y >= bx[o + 1] && y <= bx[o + 4] && z >= bx[o + 2] && z <= bx[o + 5];
        }

        /** whether box i intersects b (AABB.intersects: strict) */
        boolean meets(int i, double ax, double ay, double az, double cx, double cy, double cz) {
            int o = i * 6;
            return bx[o] < cx && bx[o + 3] > ax && bx[o + 1] < cy && bx[o + 4] > ay && bx[o + 2] < cz && bx[o + 5] > az;
        }
    }


    private static void subscribe(ES s) {
        if (s.blockBox != null) addSections(S().BLOCK_SUBS, s, s.blockBox);
        if (s.entityBox != null) addSections(S().ENTITY_SUBS, s, s.entityBox);
    }

    private static void addSections(Long2ObjectOpenHashMap<SubList> map, ES s, AABB b) {
        int x0 = SectionPos.posToSectionCoord(b.minX), x1 = SectionPos.posToSectionCoord(b.maxX);
        int y0 = SectionPos.posToSectionCoord(b.minY), y1 = SectionPos.posToSectionCoord(b.maxY);
        int z0 = SectionPos.posToSectionCoord(b.minZ), z1 = SectionPos.posToSectionCoord(b.maxZ);
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            SubList l = map.get(SectionPos.asLong(x, y, z));
            if (l == null) map.put(SectionPos.asLong(x, y, z), l = new SubList());
            l.add(s, b);
        }
    }

    /** A block (or block entity / container) changed at pos. */
    static void onPos(Level level, BlockPos pos) {
        if (S().BLOCK_SUBS.isEmpty()) return;
        SubList l = S().BLOCK_SUBS.get(SectionPos.asLong(pos));
        if (l == null) return;
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        if (x < l.u0 || x > l.u3 || y < l.u1 || y > l.u4 || z < l.u2 || z > l.u5) return;
        for (int i = 0; i < l.n; i++) {
            if (!l.holds(i, x, y, z)) continue;   // the box the entry was subscribed with: the ES only when it is hit
            ES s = l.es[i];
            if (s.st != St.ASLEEP || l.ep[i] != s.subEpoch || s.e.level() != level) continue;
            request(s, "block");
        }
    }

    /** An entity moved, appeared, changed or was removed; or a neighbour woke. */
    static void onEntity(Entity x, String reason) {
        AABB bb = x.getBoundingBox();
        onEntity(x, reason, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    /** The same over the given bounds (an entity's old and new box together when it moves). */
    static void onEntity(Entity x, String reason, double ax, double ay, double az, double bx, double by, double bz) {
        ES self = es(x);
        if (self != null && self.st == St.ASLEEP && !reason.equals("neighbour woke")) request(self, "self: " + reason);
        if (S().ENTITY_SUBS.isEmpty()) return;
        Level level = x.level();
        if (level == null || level.isClientSide && !CLIENT_ON) return;
        int x0 = SectionPos.posToSectionCoord(ax), x1 = SectionPos.posToSectionCoord(bx);
        int y0 = SectionPos.posToSectionCoord(ay), y1 = SectionPos.posToSectionCoord(by);
        int z0 = SectionPos.posToSectionCoord(az), z1 = SectionPos.posToSectionCoord(bz);
        for (int sx = x0; sx <= x1; sx++) for (int sy = y0; sy <= y1; sy++) for (int sz = z0; sz <= z1; sz++) {
            SubList l = S().ENTITY_SUBS.get(SectionPos.asLong(sx, sy, sz));
            if (l == null || l.u0 >= bx || l.u3 <= ax || l.u1 >= by || l.u4 <= ay || l.u2 >= bz || l.u5 <= az) continue;
            for (int i = 0; i < l.n; i++) {
                if (!l.meets(i, ax, ay, az, bx, by, bz)) continue;
                ES s = l.es[i];
                if (s == self || s.st != St.ASLEEP || l.ep[i] != s.subEpoch || s.e.level() != level) continue;
                // its ticks read the entities there only as collisions (getEntityCollisions: entities one can collide
                // with): one that cannot be collided with moving, joining or leaving changes nothing it read (a change of
                // its data still wakes - that may change whether it can be)
                if (!s.entityExposed && !reason.equals("data") && !x.canBeCollidedWith() && plainCollide(s.e.getClass())) continue;
                request(s, reason);
            }
        }
    }

    private static final Map<Class<?>, Boolean> PLAIN_COLLIDE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * the class keeps Entity.canCollideWith (other.canBeCollidedWith() and not the same vehicle): its collision queries see
     * only entities one can collide with (a boat's override also takes every pushable one)
     */
    private static boolean plainCollide(Class<?> c) {
        return PLAIN_COLLIDE.computeIfAbsent(c, k -> {
            for (Class<?> x = k; x != null && x != Entity.class; x = x.getSuperclass())
                for (java.lang.reflect.Method m : x.getDeclaredMethods())
                    if (m.getName().equals("canCollideWith") && m.getParameterCount() == 1) return false;
            return !dev.lull.optsupport.MixinTouch.touched("net/minecraft/world/entity/Entity", "canCollideWith");
        });
    }

    public static void onDataSet(Entity e, EntityDataAccessor<?> key, Object value) {
        ES self = es(e);
        if ((self == null || self.st != St.ASLEEP) && S().ENTITY_SUBS.isEmpty()) return;
        if (java.util.Objects.equals(e.getEntityData().get(key), value)) return;   // vanilla's no-op
        onEntity(e, "data");
    }

    // ------------------------------------------------------------------ field access (slow path, a few per replay)

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void set(Field f, Object o, Object v) {
        try {
            f.set(o, v);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long longValue(Field f, Object o) {
        try {
            Class<?> t = f.getType();
            if (t == int.class) return f.getInt(o);
            if (t == long.class) return f.getLong(o);
            if (t == short.class) return f.getShort(o);
            if (t == byte.class) return f.getByte(o);
            return 0;
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long narrow(Class<?> t, long v) {
        if (t == int.class) return (int) v;
        if (t == short.class) return (short) v;
        if (t == byte.class) return (byte) v;
        return v;
    }

    /** Write a primitive field from the snapshot encoding (raw bits for float/double, 0/1 for boolean). */
    private static void setBits(Field f, Object o, long bits) {
        try {
            Class<?> t = f.getType();
            if (t == int.class) f.setInt(o, (int) bits);
            else if (t == long.class) f.setLong(o, bits);
            else if (t == boolean.class) f.setBoolean(o, bits != 0);
            else if (t == short.class) f.setShort(o, (short) bits);
            else if (t == byte.class) f.setByte(o, (byte) bits);
            else if (t == char.class) f.setChar(o, (char) bits);
            else if (t == float.class) f.setFloat(o, Float.intBitsToFloat((int) bits));
            else if (t == double.class) f.setDouble(o, Double.longBitsToDouble(bits));
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ report

    public static String report() {
        Map<String, int[]> byType = new TreeMap<>();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "entity sleep: %s; observed ticks %d, sleeps entered %d, replayed ticks %d, inline ticks %d (failed %d, replayed once one passed %d), flips passed %d, windows not started %d, verify fails %d, deadline wakes %d, deadlines pending %d%n",
                ON ? "ON" : "OFF", S().observedTicks, S().entered, S().replayed, S().inlined, S().inlineFails, S().inlineReplayed, S().flipsPassed, S().preSkipped, S().verifyFails, S().deadlineWakes, S().DEADLINES.size()));
        sb.append("  wakes: ").append(S().WAKES).append(System.lineSeparator());
        sb.append("  refused: ").append(S().REFUSED).append(System.lineSeparator());
        if (BARRIER)
            sb.append(String.format(Locale.ROOT, "  write barrier: %d writes (not counted), %d dirty slots checked asleep, %d mirror syncs (%d full), %d slots read again, %d shared parts; draw sites numbered %d, walked %d%n",
                    S().barrierHits, S().barrierChecked, S().barrierSyncs, S().barrierFull, S().barrierReread, S().barrierShared, MobSleep.sitesGiven, MobSleep.sitesWalked));
        if (FIXED_STAT) {
            sb.append(String.format(Locale.ROOT, "  fixed point: %d of %d observed mob ticks (%.1f %%); ticks in runs of 1 / 2-3 / 4-7 / 8-15 / 16-31 / 32-63 / 64-127 / 128+: %s%n",
                    S().fixedTicks, S().fixedAll, 100.0 * S().fixedTicks / Math.max(1, S().fixedAll), java.util.Arrays.toString(S().FIXED_RUNS)));
            List<Map.Entry<String, Long>> top = new ArrayList<>(S().FIXED_BREAKERS.entrySet());
            top.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            sb.append("  fixed point broken by: ").append(top.subList(0, Math.min(20, top.size()))).append(System.lineSeparator());
        }
        if (TIMING) {
            sb.append(String.format(Locale.ROOT, "  time all entity ticks: %.3f ms per server tick over %d ticks%n", S().entityNs / 1e6 / Math.max(1, S().timedTicks), S().timedTicks));
            S().TIMES.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  time %-28s ticks %8d  total %8.1f ms  per tick %7.2f us%n", k, v[0], v[1] / 1e6, v[1] / 1e3 / Math.max(1, v[0]))));
            long rn = Math.max(1, S().RT[4]);
            sb.append(String.format(Locale.ROOT, "  time replay parts (us per replay): verify %.2f (hash %.2f, again warm %.2f, refs %.2f, clocks %.2f), checks %.2f, writes %.2f, draws %.2f; varying fields %.1f, draws %.1f per replay%n",
                    S().RT[0] / 1e3 / rn, S().RT[7] / 1e3 / rn, S().RT[9] / 1e3 / rn, S().RT[8] / 1e3 / rn, (S().RT[0] - S().RT[7] - S().RT[8]) / 1e3 / rn, S().RT[1] / 1e3 / rn, S().RT[2] / 1e3 / rn, S().RT[3] / 1e3 / rn, (double) S().RT[5] / rn, (double) S().RT[6] / rn));
            long on = Math.max(1, S().OT[2]);
            sb.append(String.format(Locale.ROOT, "  time observe parts (us per observed tick): snap before %.2f, snap after %.2f, draw sites %.2f (%.1f draws; per draw: walk %.2f, method name %.2f)%n",
                    S().OT[0] / 1e3 / on, S().OT[1] / 1e3 / on, S().OT[3] / 1e3 / on, (double) S().OT[4] / on, S().OT[5] / 1e3 / Math.max(1, S().OT[4]), S().OT[6] / 1e3 / Math.max(1, S().OT[4])));
            List<Map.Entry<String, long[]>> fl = new ArrayList<>(S().FPT.entrySet());
            fl.sort((x, y) -> Long.compare(y.getValue()[1] / Math.max(1, y.getValue()[0]), x.getValue()[1] / Math.max(1, x.getValue()[0])));
            long all = 0, alln = 0;
            for (var en : fl) {
                all += en.getValue()[1];
                alln = Math.max(alln, en.getValue()[0]);
            }
            sb.append(String.format(Locale.ROOT, "  time fingerprints: %.2f us per snapshot over %d slots; slowest:", all / 1e3 / Math.max(1, alln), fl.size()));
            for (int i = 0; i < Math.min(15, fl.size()); i++)
                sb.append(String.format(Locale.ROOT, " %s %.2f;", fl.get(i).getKey(), fl.get(i).getValue()[1] / 1e3 / Math.max(1, fl.get(i).getValue()[0])));
            sb.append(System.lineSeparator());
            sb.append(String.format(Locale.ROOT, "  time windows: %d started (contexts and epochs %.2f us each: resolve %.2f, with watch %.2f), %d complete (trySleep %.2f us each: assumptions %.2f, draw tables %.2f, look-ahead %.2f)%n",
                    S().OT[10], S().OT[9] / 1e3 / Math.max(1, S().OT[10]), S().OT[20] / 1e3 / Math.max(1, S().OT[10]), S().OT[21] / 1e3 / Math.max(1, S().OT[10]), S().OT[8], S().OT[7] / 1e3 / Math.max(1, S().OT[8]), S().OT[11] / 1e3 / Math.max(1, S().OT[14]),
                    S().OT[12] / 1e3 / Math.max(1, S().OT[14]), S().OT[13] / 1e3 / Math.max(1, S().OT[14])));
            long sn = Math.max(1, S().ST[3]);
            sb.append(String.format(Locale.ROOT, "  time mob snapshots %d: primitives %.2f us (again warm %.2f), references %.2f (again warm %.2f), fingerprints %.2f (%.1f slots again)%n",
                    S().ST[3], S().ST[0] / 1e3 / sn, S().ST[5] / 1e3 / sn, S().ST[1] / 1e3 / sn, S().ST[6] / 1e3 / sn, S().ST[2] / 1e3 / sn, (double) S().ST[4] / sn));
            long en = Math.max(1, S().OT[19]);
            sb.append(String.format(Locale.ROOT, "  time sleeps entered %d: up to the deadline %.2f us, deadline and checks %.2f, phase hashes and tables %.2f, subscriptions %.2f%n",
                    S().OT[19], S().OT[15] / 1e3 / en, S().OT[16] / 1e3 / en, S().OT[17] / 1e3 / en, S().OT[18] / 1e3 / en));
            sb.append(String.format(Locale.ROOT, "  time vanilla tick inside observed ticks %8d  total %8.1f ms  per tick %7.2f us%n", S().innerN, S().innerNs / 1e6, S().innerNs / 1e3 / Math.max(1, S().innerN)));
        }
        return sb.toString();
    }

    /** Asleep / tracked counts per entity type in a level. */
    public static String census(net.minecraft.server.level.ServerLevel level) {
        Map<String, int[]> byType = new TreeMap<>();
        for (Entity e : level.getAllEntities()) {
            ES s = es(e);
            int[] v = byType.computeIfAbsent(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), k -> new int[3]);
            v[0]++;
            if (s != null && s.st == St.ASLEEP) v[1]++;
            if (s != null && s.st == St.NEVER) v[2]++;
        }
        StringBuilder sb = new StringBuilder();
        byType.forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  %-34s entities %6d  asleep %6d  never %6d%n", k, v[0], v[1], v[2])));
        return sb.toString();
    }

    /**
     * Exactness checks ({@code sleep hash} with -Dsleep.hashEntitiesDeep=true): every primitive field and every value
     * object (vectors, boxes, positions, synched data, random seeds, boxed values, enums) of the entity, by value.
     * Identity-only references, ids and UUIDs are left out (they differ between runs without meaning anything).
     */
    /** Like EntityFp.fp but equal across runs: item stacks by registry id, count and components; synched values by content. */
    private static long stableFp(Object r) {
        return stableFp(r, 0);
    }

    private static long stableFp(Object r, int depth) {
        if (r instanceof net.minecraft.world.item.ItemStack st)
            return st.isEmpty() ? 0 : BuiltInRegistries.ITEM.getKey(st.getItem()).hashCode() * 1_000_003L + st.getCount() * 7919L + st.getComponentsPatch().toString().hashCode();
        if (r instanceof SynchedEntityData d) {
            long h = d.isDirty() ? 1 : 0;
            for (SynchedEntityData.DataValue<?> dv : d.getNonDefaultValues() == null ? List.<SynchedEntityData.DataValue<?>>of() : d.getNonDefaultValues())
                h = h * 31 + dv.id() * 7919L + stableFp(dv.value(), depth + 1);
            return h;
        }
        if (r instanceof Vec3 || r instanceof AABB || r instanceof BlockPos) return EntityFp.fp(r);
        if (r instanceof Enum<?> en) return en.getDeclaringClass().getName().hashCode() * 31L + en.ordinal();   // Enum.hashCode is per run
        if (r == null) return 0;
        if (depth < 4 && r instanceof Iterable<?> it) {   // lists by their elements (a villager's offers)
            long h = 1;
            for (Object o : it) h = h * 31 + stableFp(o, depth + 1);
            return h;
        }
        if (r instanceof java.util.Optional<?> o) return o.isEmpty() ? 2 : 3 * 31L + stableFp(o.get(), depth + 1);
        String s = r.toString();
        if (depth < 4 && s.startsWith(r.getClass().getName() + "@")) {
            // Object's toString names the identity (per run): by the fields' values instead (VillagerData, MerchantOffer)
            long h = r.getClass().getName().hashCode();
            for (Field f : S().DFIELDS.computeIfAbsent(r.getClass(), EntitySleep::instanceFields)) {
                try {
                    Class<?> t = f.getType();
                    long v = t == int.class ? f.getInt(r) : t == long.class ? f.getLong(r) : t == boolean.class ? (f.getBoolean(r) ? 1 : 0)
                            : t == float.class ? Float.floatToRawIntBits(f.getFloat(r)) : t == double.class ? Double.doubleToRawLongBits(f.getDouble(r))
                            : t.isPrimitive() ? String.valueOf(f.get(r)).hashCode() : stableFp(f.get(r), depth + 1);
                    h = h * 1_000_003L + f.getName().hashCode() * 31L + v;
                } catch (IllegalAccessException ex) {
                    h = h * 31 + 1;
                }
            }
            return h;
        }
        return s.hashCode();
    }

    public static String deepDump(Entity e) {
        StringBuilder sb = new StringBuilder();
        for (Field f : S().DFIELDS.computeIfAbsent(e.getClass(), EntitySleep::instanceFields)) sb.append(f.getName()).append('=').append(show(f, e)).append("; ");
        return sb.toString();
    }

    public static long deepHash(Entity e) {
        Field[] fs = S().DFIELDS.computeIfAbsent(e.getClass(), EntitySleep::instanceFields);
        long h = 17;
        for (Field f : fs) {
            String n = f.getName();
            if (n.equals("id") || n.equals("uuid") || n.equals("stringUUID") || n.startsWith("sleep$") || n.startsWith("probe$") || n.equals("bobOffs") || n.equals("timeOffs") || n.equals("rotA")) continue;   // per run (timeOffs, rotA: Math.random() in LivingEntity's constructor, cosmetic)
            Class<?> t = f.getType();
            // (random seeds are per run: RandomSource.create() is seeded from the clock)
            if (!t.isPrimitive() && !(valueType(t) || SynchedEntityData.class.isAssignableFrom(t))) continue;
            try {
                long v = t == int.class ? f.getInt(e) : t == long.class ? f.getLong(e) : t == boolean.class ? (f.getBoolean(e) ? 1 : 0)
                        : t == short.class ? f.getShort(e) : t == byte.class ? f.getByte(e) : t == float.class ? Float.floatToRawIntBits(f.getFloat(e))
                        : t == double.class ? Double.doubleToRawLongBits(f.getDouble(e)) : t == char.class ? f.getChar(e) : stableFp(f.get(e));
                h = h * 1_000_003L + n.hashCode() * 31L + v;
            } catch (IllegalAccessException ex) {
                h = h * 31 + 1;
            }
        }
        return h;
    }

    // ------------------------------------------------------------------ diagnostic

    private static final int DIAG_FROM = Integer.getInteger("lab.entsleep.diagFrom", 300), DIAG_TICKS = Integer.getInteger("lab.entsleep.diagTicks", 12);

    private static void diag(Entity e) {
        int id = e.getId();
        if (!S().PICKED.contains(id)) {
            if (S().PICKED.size() >= 3 || e.tickCount < DIAG_FROM) {
                e.tick();
                return;
            }
            S().PICKED.add(id);
        }
        Field[] fs = S().DFIELDS.computeIfAbsent(e.getClass(), EntitySleep::instanceFields);
        long[] before = dsnap(e, fs);
        e.tick();
        long[] after = dsnap(e, fs);
        long[] prev = S().DPREV.put(id, after);
        StringBuilder sb = new StringBuilder("[sleep/entsleep] ").append(e.getClass().getSimpleName()).append(" #").append(id).append(" t=").append(e.tickCount).append(':');
        for (int i = 0; i < fs.length; i++) if (before[i] != after[i]) sb.append(' ').append(fs[i].getName()).append('=').append(show(fs[i], e));
        if (prev != null) {
            sb.append("  | outside tick:");
            for (int i = 0; i < fs.length; i++) if (prev[i] != before[i]) sb.append(' ').append(fs[i].getName());
        }
        System.out.println(sb);
        if (e.tickCount >= DIAG_FROM + DIAG_TICKS) S().PICKED.remove((Integer) id);
    }

    static Field[] instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out.toArray(new Field[0]);
    }

    private static long[] dsnap(Entity e, Field[] fs) {
        long[] v = new long[fs.length];
        for (int i = 0; i < fs.length; i++) {
            try {
                Field f = fs[i];
                Class<?> t = f.getType();
                v[i] = t == int.class ? f.getInt(e) : t == long.class ? f.getLong(e) : t == boolean.class ? (f.getBoolean(e) ? 1 : 0)
                        : t == short.class ? f.getShort(e) : t == byte.class ? f.getByte(e) : t == float.class ? Float.floatToRawIntBits(f.getFloat(e))
                        : t == double.class ? Double.doubleToRawLongBits(f.getDouble(e)) : t == char.class ? f.getChar(e) : EntityFp.fp(f.get(e));
            } catch (IllegalAccessException ex) {
                v[i] = 0;
            }
        }
        return v;
    }

    private static String show(Field f, Object o) {
        try {
            Object v = f.get(o);
            String s = v instanceof SynchedEntityData d ? "synched#" + Long.toHexString(EntityFp.fp(d)) : String.valueOf(v);
            return s.length() > 60 ? s.substring(0, 60) : s;
        } catch (IllegalAccessException e) {
            return "?";
        }
    }
}
