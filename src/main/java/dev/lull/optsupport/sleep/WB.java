package dev.lull.optsupport.sleep;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;

/**
 * The write barrier's runtime (WriteBarrier puts {@code WB.w<root>(obj, id)} in front of every write into a root's
 * fields). {@code w<k>} reads the object's watch word at a constant offset; zero (nobody watching) is the whole cost.
 * A set word sends the write to {@link #hit}.
 * <p>
 * Measurement mode {@code -Dlab.wb.sample=N}: the first N animals' parts are watched; per tick of such an animal every
 * write is logged into a ring (object, field, old value); after the tick the fields really changed are counted.
 * Writes to watched objects outside their own tick are counted too (foreign writers). Report every 400 ticks.
 */
public final class WB {
    static final sun.misc.Unsafe U = SnapCompiler.U;
    static final long O0, O1, O2, O3, O4, O5, O6, O7, O8, O9, O10, O11, O12, O13, O14, O15, O16, O17, O18, O19, O20, O21, O22, O23;
    static final int SAMPLE = Integer.getInteger("lab.wb.sample", 0);

    static {
        if (WriteBarrier.ROOTS.length > 24) throw new IllegalStateException("[sleep/wb] more roots than barrier methods");
        long[] o = new long[WriteBarrier.ROOTS.length];
        for (int k = 0; k < o.length; k++) {
            try {
                Class<?> c = Class.forName(WriteBarrier.ROOTS[k].replace('/', '.'), false, WB.class.getClassLoader());
                o[k] = U.objectFieldOffset(c.getDeclaredField(WriteBarrier.FIELD));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("[sleep/wb] no watch word in " + WriteBarrier.ROOTS[k], e);
            }
        }
        O0 = o.length > 0 ? o[0] : -1; O1 = o.length > 1 ? o[1] : -1; O2 = o.length > 2 ? o[2] : -1; O3 = o.length > 3 ? o[3] : -1; O4 = o.length > 4 ? o[4] : -1; O5 = o.length > 5 ? o[5] : -1; O6 = o.length > 6 ? o[6] : -1; O7 = o.length > 7 ? o[7] : -1; O8 = o.length > 8 ? o[8] : -1; O9 = o.length > 9 ? o[9] : -1; O10 = o.length > 10 ? o[10] : -1; O11 = o.length > 11 ? o[11] : -1; O12 = o.length > 12 ? o[12] : -1; O13 = o.length > 13 ? o[13] : -1; O14 = o.length > 14 ? o[14] : -1; O15 = o.length > 15 ? o[15] : -1; O16 = o.length > 16 ? o[16] : -1; O17 = o.length > 17 ? o[17] : -1; O18 = o.length > 18 ? o[18] : -1; O19 = o.length > 19 ? o[19] : -1; O20 = o.length > 20 ? o[20] : -1; O21 = o.length > 21 ? o[21] : -1; O22 = o.length > 22 ? o[22] : -1; O23 = o.length > 23 ? o[23] : -1;
        if (SAMPLE > 0) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WB::onPre);
        if (SAMPLE > 0) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WB::onPost);
        System.out.println("[sleep/wb] write barrier: " + WriteBarrier.sites + " sites in " + WriteBarrier.classes + " classes so far, " + WBIds.count + " fields");
    }

    private WB() {}

    // ------------------------------------------------------------------ the barrier (one per root: constant offsets)

    public static void w0(Object o, int id) { Object w = U.getObject(o, O0); if (w != null || TX) hit(o, id, w, 0); }
    public static void w1(Object o, int id) { Object w = U.getObject(o, O1); if (w != null || TX) hit(o, id, w, 1); }
    public static void w2(Object o, int id) { Object w = U.getObject(o, O2); if (w != null || TX) hit(o, id, w, 2); }
    public static void w3(Object o, int id) { Object w = U.getObject(o, O3); if (w != null || TX) hit(o, id, w, 3); }
    public static void w4(Object o, int id) { Object w = U.getObject(o, O4); if (w != null || TX) hit(o, id, w, 4); }
    public static void w5(Object o, int id) { Object w = U.getObject(o, O5); if (w != null || TX) hit(o, id, w, 5); }
    public static void w6(Object o, int id) { Object w = U.getObject(o, O6); if (w != null || TX) hit(o, id, w, 6); }
    public static void w7(Object o, int id) { Object w = U.getObject(o, O7); if (w != null || TX) hit(o, id, w, 7); }
    public static void w8(Object o, int id) { Object w = U.getObject(o, O8); if (w != null || TX) hit(o, id, w, 8); }
    public static void w9(Object o, int id) { Object w = U.getObject(o, O9); if (w != null || TX) hit(o, id, w, 9); }
    public static void w10(Object o, int id) { Object w = U.getObject(o, O10); if (w != null || TX) hit(o, id, w, 10); }
    public static void w11(Object o, int id) { Object w = U.getObject(o, O11); if (w != null || TX) hit(o, id, w, 11); }
    public static void w12(Object o, int id) { Object w = U.getObject(o, O12); if (w != null || TX) hit(o, id, w, 12); }
    public static void w13(Object o, int id) { Object w = U.getObject(o, O13); if (w != null || TX) hit(o, id, w, 13); }
    public static void w14(Object o, int id) { Object w = U.getObject(o, O14); if (w != null || TX) hit(o, id, w, 14); }
    public static void w15(Object o, int id) { Object w = U.getObject(o, O15); if (w != null || TX) hit(o, id, w, 15); }
    public static void w16(Object o, int id) { Object w = U.getObject(o, O16); if (w != null || TX) hit(o, id, w, 16); }
    public static void w17(Object o, int id) { Object w = U.getObject(o, O17); if (w != null || TX) hit(o, id, w, 17); }
    public static void w18(Object o, int id) { Object w = U.getObject(o, O18); if (w != null || TX) hit(o, id, w, 18); }
    public static void w19(Object o, int id) { Object w = U.getObject(o, O19); if (w != null || TX) hit(o, id, w, 19); }
    public static void w20(Object o, int id) { Object w = U.getObject(o, O20); if (w != null || TX) hit(o, id, w, 20); }
    public static void w21(Object o, int id) { Object w = U.getObject(o, O21); if (w != null || TX) hit(o, id, w, 21); }
    public static void w22(Object o, int id) { Object w = U.getObject(o, O22); if (w != null || TX) hit(o, id, w, 22); }
    public static void w23(Object o, int id) { Object w = U.getObject(o, O23); if (w != null || TX) hit(o, id, w, 23); }

    /** sets an object's watcher (null: unwatched); false if its class has no watcher field */
    static boolean watch(Object o, Object watcher) {
        if (o == null) return true;
        int k = rootIndex(o.getClass());
        if (k >= 0) {
            U.putObject(o, offset(k), watcher);
            return true;
        }
        unrooted.merge(o.getClass().getSimpleName(), 1, Integer::sum);
        return false;
    }

    /** an object's watcher (null if none or not a root's) */
    static Object watcherOf(Object o) {
        if (o == null) return null;
        int k = rootIndex(o.getClass());
        return k < 0 ? null : U.getObject(o, offset(k));
    }

    /** measurement (dev.lull.TickCensus): a part watched by its owner unit; parts of a sleeping mob keep their watcher */
    public static String censusLabel(int id) {
        return WBIds.label(id);
    }

    public static void censusWatch(Object o, Object owner) {
        Object w = watcherOf(o);
        if (w == null || w instanceof dev.lull.TickCensus.Owner) watch(o, owner);
    }

    private static final Object SAMPLED = new Object();

    private static void sample(Object o) {
        watch(o, SAMPLED);
    }

    private static final Map<Class<?>, Integer> ROOT_INDEX = new HashMap<>();
    private static final Map<String, Integer> unrooted = new HashMap<>();

    static synchronized int rootIndex(Class<?> c) {
        Integer r = ROOT_INDEX.get(c);
        if (r != null) return r;
        int found = -1;
        for (Class<?> x = c; x != null && found < 0; x = x.getSuperclass())
            for (int k = 0; k < WriteBarrier.ROOTS.length; k++) if (WriteBarrier.ROOTS[k].equals(x.getName().replace('.', '/'))) found = k;
        ROOT_INDEX.put(c, found);
        return found;
    }

    static long offset(int k) {
        return switch (k) {
            case 0 -> O0;
            case 1 -> O1;
            case 2 -> O2;
            case 3 -> O3;
            case 4 -> O4;
            case 5 -> O5;
            case 6 -> O6;
            case 7 -> O7;
            case 8 -> O8;
            case 9 -> O9;
            case 10 -> O10;
            case 11 -> O11;
            case 12 -> O12;
            case 13 -> O13;
            case 14 -> O14;
            case 15 -> O15;
            case 16 -> O16;
            case 17 -> O17;
            case 18 -> O18;
            case 19 -> O19;
            case 20 -> O20;
            case 21 -> O21;
            case 22 -> O22;
            default -> O23;
        };
    }

    // ------------------------------------------------------------------ measurement: the write log of sampled animals

    private static final int RING = 1 << 12;
    private static final Object[] LOG_O = new Object[RING], LOG_REF = new Object[RING];
    private static final int[] LOG_ID = new int[RING];
    private static final long[] LOG_OLD = new long[RING];
    private static int logN;
    private static Entity cur;   // the sampled animal whose tick is running
    private static long[] off = new long[0];
    private static long hitsIn, hitsOut, ticks, distinct, changed, overflow;
    private static long[] changedBy = new long[0], writtenBy = new long[0], outBy = new long[0];
    private static final List<Entity> sampled = new ArrayList<>();
    private static long reportAt = 400;

    /**
     * SpecTicks' transaction mode (SpecTx): while a parallel run ticks, every write into a root object goes to the
     * writing thread's transaction (copy on first write, conflicts between transactions); outside runs false.
     */
    public static volatile boolean TX;
    /** writes outside runs into an object still holding a live transaction's word (should stay 0) */
    public static long liveOutside;

    /** a mutating call on a collection, map or atomic in a barrier class (WriteBarrier.mutSites): to the transaction */
    public static void mut(Object c) {
        if (TX) SpecTx.mutated(c);
    }

    static void hit(Object o, int id, Object w, int root) {
        if (w instanceof SpecTx.Token && SpecTx.mine(w)) return;   // (copied already in this tick)
        if (TX) {
            SpecTx tx = SpecTx.current();
            if (tx != null && tx.active) {
                tx.wrote(o, w, root);
                if (w == null || w instanceof SpecTx.Token) return;
            }
            if (w == null || w instanceof SpecTx.Token) return;
        }
        if (w instanceof SpecTx.Token t) {
            if (!t.live) U.putObject(o, offset(root), null);   // (a released transaction's word: gone for the next writes)
            else if (!TX) liveOutside++;   // (measurement: a live transaction's word met outside runs)
            return;
        }
        if (w instanceof EntitySleep.PartWatch pw) pw.write(id);
        else if (w instanceof EntitySleep.SharedWatch sw) EntitySleep.onWrite(sw, id);
        else if (w instanceof dev.lull.TickCensus.Owner ow) dev.lull.TickCensus.write(ow, id);
        else if (w instanceof SpecTx.Token) return;
        else sampleHit(o, id);
    }

    private static void sampleHit(Object o, int id) {
        if (cur == null) {
            hitsOut++;
            outBy = grow(outBy, id);
            outBy[id]++;
            return;
        }
        hitsIn++;
        writtenBy = grow(writtenBy, id);
        writtenBy[id]++;
        if (logN == RING) {
            overflow++;
            return;
        }
        long f = fieldOffset(id, o.getClass());
        LOG_O[logN] = o;
        LOG_ID[logN] = id;
        char t = WBIds.desc[id].charAt(0);
        if (t == 'L' || t == '[') LOG_REF[logN] = U.getObject(o, f);
        else LOG_OLD[logN] = bits(o, f, t);
        logN++;
    }

    static long bits(Object o, long f, char t) {
        return switch (t) {
            case 'Z' -> U.getBoolean(o, f) ? 1 : 0;
            case 'B' -> U.getByte(o, f);
            case 'C' -> U.getChar(o, f);
            case 'S' -> U.getShort(o, f);
            case 'I' -> U.getInt(o, f);
            case 'F' -> Float.floatToRawIntBits(U.getFloat(o, f));
            case 'J' -> U.getLong(o, f);
            case 'D' -> Double.doubleToRawLongBits(U.getDouble(o, f));
            default -> 0;
        };
    }

    static long fieldOffset(int id, Class<?> c) {
        if (off.length <= id) {
            int n = Math.max(WBIds.count, id + 1);
            long[] a = java.util.Arrays.copyOf(off, n);
            java.util.Arrays.fill(a, off.length, n, -1);
            off = a;
        }
        if (off[id] >= 0) return off[id];
        for (Class<?> x = c; x != null; x = x.getSuperclass())
            for (Field fl : x.getDeclaredFields())
                if (fl.getName().equals(WBIds.name[id]) && !java.lang.reflect.Modifier.isStatic(fl.getModifiers())) return off[id] = U.objectFieldOffset(fl);
        throw new IllegalStateException("[sleep/wb] field " + WBIds.label(id));
    }

    private static long[] grow(long[] a, int id) {
        return a.length > id ? a : java.util.Arrays.copyOf(a, Math.max(WBIds.count, id + 1));
    }

    private static Field bodyRot;

    static void onPre(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre e) {
        Entity en = e.getEntity();
        if (en.level().isClientSide) return;
        if (sampled.size() < SAMPLE && en instanceof Animal && !sampled.contains(en)) sampled.add(en);
        if (!sampled.contains(en)) return;
        Mob m = (Mob) en;
        // (re)watch the parts: the path is a new object each time the navigation plans one
        sample(m);
        sample(m.goalSelector);
        sample(m.targetSelector);
        for (WrappedGoal g : m.goalSelector.getAvailableGoals()) {
            sample(g);
            sample(g.getGoal());
        }
        for (WrappedGoal g : m.targetSelector.getAvailableGoals()) {
            sample(g);
            sample(g.getGoal());
        }
        sample(m.getMoveControl());
        sample(m.getLookControl());
        sample(m.getJumpControl());
        sample(m.getNavigation());
        sample(m.getNavigation().getPath());
        sample(m.getBrain());
        try {
            if (bodyRot == null) {
                bodyRot = Mob.class.getDeclaredField("bodyRotationControl");
                bodyRot.setAccessible(true);
            }
            sample(bodyRot.get(m));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        cur = en;
        logN = 0;
    }

    static void onPost(net.neoforged.neoforge.event.tick.EntityTickEvent.Post e) {
        if (cur != e.getEntity()) return;
        cur = null;
        ticks++;
        changedBy = grow(changedBy, WBIds.count - 1);
        for (int i = 0; i < logN; i++) {
            boolean first = true;
            for (int j = 0; j < i && first; j++) first = !(LOG_O[j] == LOG_O[i] && LOG_ID[j] == LOG_ID[i]);
            if (!first) continue;
            distinct++;
            int id = LOG_ID[i];
            long f = fieldOffset(id, LOG_O[i].getClass());
            char t = WBIds.desc[id].charAt(0);
            boolean ch = t == 'L' || t == '[' ? U.getObject(LOG_O[i], f) != LOG_REF[i] : bits(LOG_O[i], f, t) != LOG_OLD[i];
            if (ch) {
                changed++;
                changedBy[id]++;
            }
            LOG_O[i] = LOG_REF[i] = null;
        }
        if (ticks >= reportAt) {
            reportAt = ticks + 400;
            report();
        }
    }

    static void report() {
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "[sleep/wb] %d sampled ticks: writes %.1f/tick, distinct fields %.1f/tick, really changed %.2f/tick; outside the tick %.2f/tick; overflow %d%n",
                ticks, hitsIn / (double) ticks, distinct / (double) ticks, changed / (double) ticks, hitsOut / (double) ticks, overflow));
        sb.append("  changed (per 100 ticks):");
        top(sb, changedBy, 30);
        sb.append("\n  written (per 100 ticks):");
        top(sb, writtenBy, 30);
        sb.append("\n  outside the tick (per 100 ticks):");
        top(sb, outBy, 20);
        sb.append("\n  watched objects without a root: ").append(unrooted);
        System.out.println(sb);
    }

    private static void top(StringBuilder sb, long[] a, int n) {
        Integer[] ix = new Integer[a.length];
        for (int i = 0; i < a.length; i++) ix[i] = i;
        java.util.Arrays.sort(ix, (x, y) -> Long.compare(a[y], a[x]));
        for (int i = 0; i < Math.min(n, ix.length) && a[ix[i]] > 0; i++)
            sb.append(String.format(java.util.Locale.ROOT, " %s %.1f", WBIds.label(ix[i]), a[ix[i]] * 100.0 / ticks));
    }
}
