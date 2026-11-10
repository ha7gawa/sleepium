package dev.lull.optsupport.sleep;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;

/**
 * Mob sleep at run time (notes/47 steps B and C; -Dlab.opt.EntitySleep with -Dlab.sleep.components and
 * -Dlab.sleep.mobRuntime). Two pieces EntitySleep uses for classes whose plan has a {@link SleepAnalysis.MobPlan}:
 *
 * <p><b>The composite state.</b> The entity's own fields are part 0; every context the analysis followed (the
 * controls, the navigation, the goal selectors, each wrapped goal and goal) is a further part, resolved from the
 * entity by its path. Each part has its own compiled Snapper; {@link Composite} lays them end to end as one Snapper,
 * so EntitySleep's observation, periodicity check, replay writes and replay check work on all of them at once.
 *
 * <p><b>The draws.</b> While an entity is observed, every draw of its own random (LegacyRandomSource, through
 * MobSleepRandomMixin) is recorded with its call site, kind and bound. Each must be at a draw condition of the plan
 * (a draw compared with a constant, a clock or a final field). The replay makes the same draws on the real random -
 * the seed advances exactly as the ticks would have advanced it - and the deadline is the first tick at which a
 * simulated copy of the seed would take another branch at one of them.
 */
final class MobSleep {
    static final boolean ON = Boolean.getBoolean("lab.sleep.components") && Boolean.getBoolean("lab.sleep.mobRuntime");

    private MobSleep() {}

    // ------------------------------------------------------------------ paths

    /** per class and name (the analysis resolves on its own thread too) */
    private static final Map<Class<?>, Map<String, java.util.Optional<Field>>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    static Field field(Class<?> c, String name) {
        return FIELDS.computeIfAbsent(c, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(name, x -> java.util.Optional.ofNullable(findField(c, x))).orElse(null);
    }

    private static Field findField(Class<?> c, String name) {
        Field r = null;
        for (Class<?> k = c; k != null && k != Object.class && r == null; k = k.getSuperclass())
            try {
                Field f = k.getDeclaredField(dev.lull.Names.field(k.getName().replace('.', '/'), name));   // SRG in production (1.20.1)
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    r = f;
                }
            } catch (NoSuchFieldException | RuntimeException e) {
                // the superclass
            }
        return r;
    }

    static Object fieldValue(Object o, String name) {
        Field f = o == null ? null : field(o.getClass(), name);
        try {
            return f == null ? null : f.get(o);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** the object at a path from the entity ("" the entity, "goalSelector.availableGoals[3].goal") */
    static Object resolve(Object root, String path) {
        if (path.isEmpty()) return root;
        return PATHS.computeIfAbsent(path, Path::new).get(root);
    }

    private static final Map<String, Path> PATHS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A path compiled once: its steps, each a field name and an optional collection index, with the field found for the
     * last class seen at that step (an immutable pair, swapped whole: the analysis resolves paths on its own thread).
     */
    private static final class Path {
        private record Hop(Class<?> cls, Field f, long off) {}   // off: the reference field's offset (-1: through f)

        final String[] names;
        final int[] index;
        final Hop[] hops;

        Path(String path) {
            String[] parts = path.split("\\.");
            names = new String[parts.length];
            index = new int[parts.length];
            hops = new Hop[parts.length];
            for (int i = 0; i < parts.length; i++) {
                int br = parts[i].indexOf('[');
                names[i] = br < 0 ? parts[i] : parts[i].substring(0, br);
                index[i] = br < 0 ? -1 : Integer.parseInt(parts[i].substring(br + 1, parts[i].length() - 1));
            }
        }

        // resolveAll's paths share their collections (goalSelector.availableGoals[0..n]): each one is walked once per
        // call (server thread: the only caller of resolveAll; single paths elsewhere walk the collection)
        private static final Object[] MEMO_COL = new Object[4];
        private static final Object[][] MEMO_ELS = new Object[4][];
        private static int memoN;
        static boolean memoOn;

        static void memoClear() {
            for (int i = 0; i < memoN; i++) {
                MEMO_COL[i] = null;
                MEMO_ELS[i] = null;
            }
            memoN = 0;
        }

        private static Object[] memo(java.util.Collection<?> col) {
            if (!memoOn) return col.toArray();
            for (int i = 0; i < memoN; i++) if (MEMO_COL[i] == col) return MEMO_ELS[i];
            Object[] els = col.toArray();
            if (memoN < MEMO_COL.length) {
                MEMO_COL[memoN] = col;
                MEMO_ELS[memoN++] = els;
            }
            return els;
        }

        Object get(Object root) {
            Object o = root;
            for (int i = 0; i < names.length; i++) {
                if (o == null) return null;
                Hop h = hops[i];
                Class<?> c = o.getClass();
                if (h == null || h.cls != c) {
                    Field f = field(c, names[i]);
                    hops[i] = h = new Hop(c, f, f != null && !f.getType().isPrimitive() && SnapCompiler.U != null ? SnapCompiler.U.objectFieldOffset(f) : -1);
                }
                try {
                    o = h.f == null ? null : h.off >= 0 ? SnapCompiler.U.getObject(o, h.off) : h.f.get(o);
                } catch (IllegalAccessException e) {
                    return null;
                }
                int k = index[i];
                if (k >= 0) {
                    if (!(o instanceof java.util.Collection<?> col)) return null;
                    Object[] els = memo(col);
                    o = k < els.length ? els[k] : null;
                }
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ the composite state

    /** One object of the state: its fields are slots off .. off + f.length - 1 of the composite. */
    static final class Part {
        final String path;
        final Class<?> cls;
        final Field[] f;
        final int off;
        final boolean[] prim, special;
        final int[] refSlots;   // compact, part-local
        final int refOff;       // where this part's compact references start in the composite's
        final SnapCompiler.Snapper snap;
        final long[] scratch;
        final Object[] rscratch, refScratch;

        Part(String path, Class<?> cls, List<Field> fields, int off, boolean[] special, int refOff) {
            this.path = path;
            this.cls = cls;
            this.f = fields.toArray(new Field[0]);
            this.off = off;
            this.special = special;
            prim = new boolean[f.length];
            for (int i = 0; i < f.length; i++) prim[i] = f[i].getType().isPrimitive();
            snap = SnapCompiler.compile(fields, true, special);
            refSlots = SnapCompiler.refSlots(fields, special);
            this.refOff = refOff;
            scratch = new long[f.length];
            rscratch = new Object[f.length];
            refScratch = new Object[refSlots.length];
        }
    }

    static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                out.add(f);
            }
        return out;
    }

    /**
     * The parts as one Snapper over the entity: each call resolves the parts' objects from the entity's ES (resolved
     * when an observation starts, checked by class). Scratch arrays per part: server thread only.
     */
    static final class Composite implements SnapCompiler.Snapper {
        final Part[] parts;
        final int n;
        final int[] partOf;
        /** one generated class over all the slots (receivers from the ES's context array); null: the per-part path */
        final SnapCompiler.Snapper all;
        final boolean[] prim, special;
        final int[] refAll;   // every reference slot
        final int[] order, parentPart;   // resolution: parts by depth, each from its nearest part ancestor
        final Path[] rel;                // ... by the path from that ancestor (one hop for most)

        Composite(Part[] parts, List<Field> fields, boolean[] special) {
            this.parts = parts;
            int np = parts.length;
            parentPart = new int[np];
            rel = new Path[np];
            Integer[] ord = new Integer[np];
            for (int k = 0; k < np; k++) {
                ord[k] = k;
                String pk = parts[k].path;
                int best = -1;
                for (int j = 0; j < np; j++) {
                    String pj = parts[j].path;
                    if (j == k || pj.length() >= pk.length() || !(pj.isEmpty() || pk.startsWith(pj + "."))) continue;
                    if (best < 0 || pj.length() > parts[best].path.length()) best = j;
                }
                parentPart[k] = best;
                if (!pk.isEmpty()) rel[k] = new Path(best < 0 || parts[best].path.isEmpty() ? pk : pk.substring(parts[best].path.length() + 1));
            }
            java.util.Arrays.sort(ord, java.util.Comparator.comparingInt(k -> parts[k].path.isEmpty() ? 0 : (int) parts[k].path.chars().filter(ch -> ch == '.').count() + 1));
            order = new int[np];
            for (int k = 0; k < np; k++) order[k] = ord[k];
            Part last = parts[parts.length - 1];
            n = last.off + last.f.length;
            partOf = new int[n];
            for (int k = 0; k < parts.length; k++) for (int i = 0; i < parts[k].f.length; i++) partOf[parts[k].off + i] = k;
            this.special = special;
            prim = new boolean[n];
            for (int i = 0; i < n; i++) prim[i] = fields.get(i).getType().isPrimitive();
            all = SnapCompiler.compileComposite(fields, special, partOf);
            int nr = 0;
            for (int i = 0; i < n; i++) if (!prim[i]) nr++;
            refAll = new int[nr];
            for (int i = 0, k = 0; i < n; i++) if (!prim[i]) refAll[k++] = i;
        }

        private static Object[] objs(Object be) {
            return EntitySleep.esOf((Entity) be).ctx;
        }

        @Override
        public void snap(Object be, long[] out) {
            Object[] o = objs(be);
            if (all != null) {
                all.snap(o, out);
                return;
            }
            for (int k = 0; k < parts.length; k++) {
                Part p = parts[k];
                p.snap.snap(o[k], p.scratch);
                System.arraycopy(p.scratch, 0, out, p.off, p.f.length);
            }
        }

        @Override
        public void refs(Object be, Object[] out) {
            Object[] o = objs(be);
            if (all != null) {
                all.refs(o, out);
                return;
            }
            for (int k = 0; k < parts.length; k++) {
                Part p = parts[k];
                // primitive slots untouched: copy only the reference slots
                for (int i = 0; i < p.f.length; i++) p.rscratch[i] = out[p.off + i];
                p.snap.refs(o[k], p.rscratch);
                System.arraycopy(p.rscratch, 0, out, p.off, p.f.length);
            }
        }

        @Override
        public boolean same(Object be, long[] prims, Object[] refs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void both(Object be, long[] out, Object[] refs) {
            Object[] o = objs(be);
            if (all != null) {
                all.both(o, out, refs);
                return;
            }
            snap(be, out);
            refs(be, refs);
        }

        @Override
        public void prims(Object be, long[] out) {
            Object[] o = objs(be);
            if (all != null) {
                all.prims(o, out);
                return;
            }
            snap(be, out);
        }

        /**
         * snap() for an observed tick: the same values, but a reference slot's fingerprint is taken from the previous
         * snapshot ({@code prevOut}, its objects {@code prevRefs}) when the slot holds the same object and that
         * object's fingerprint is its identity or an immutable value - only replaced objects and containers (whose
         * content can change in place) are fingerprinted again; with {@code sameContent} containers too (the state
         * before a tick, taken as the previous tick's after-state: a container changed in between shows in this tick's
         * after-state). {@code content[i]}: whether the object in slot i is a container, kept per slot so an unchanged
         * object is not touched at all (the cost of a snapshot is its cache misses: ~120 objects). {@code refsOut}:
         * this snapshot's objects.
         */
        void snapObserved(Object be, long[] out, Object[] refsOut, Object[] prevRefs, long[] prevOut, boolean[] content, boolean sameContent) {
            if (all == null) {
                snap(be, out);
                refs(be, refsOut);
                return;
            }
            long t0 = EntitySleep.TIMING ? System.nanoTime() : 0;
            Object[] o = objs(be);
            all.both(o, out, refsOut);
            long t1 = EntitySleep.TIMING ? System.nanoTime() : 0;
            long t2 = t1;
            int again = 0;
            for (int i : refAll) {
                Object r = refsOut[i];
                if (prevRefs != null && r == prevRefs[i] && (sameContent || !content[i])) {
                    out[i] = prevOut[i];
                } else {
                    content[i] = EntityFp.content(r);
                    out[i] = EntityFp.observedFp(r);
                    again++;
                }
            }
            if (EntitySleep.TIMING) {
                long[] t = EntitySleep.S().ST;
                long w0 = System.nanoTime();
                all.both(o, out, refsOut);   // again, the data now in the cache
                long w1 = System.nanoTime();
                long w2 = w1;
                t[5] += w1 - w0;
                t[6] += w2 - w1;
                t[0] += t1 - t0;
                t[1] += t2 - t1;
                t[2] += System.nanoTime() - t2;
                t[3]++;
                t[4] += again;
            }
        }

        @Override
        public void put(Object be, int i, long bits, Object ref) {
            if (all != null) {
                all.put(objs(be), i, bits, ref);
                return;
            }
            int k = partOf[i];
            Part p = parts[k];
            p.snap.put(objs(be)[k], i - p.off, bits, ref);
        }

        @Override
        public long bits(Object be, int i) {
            if (all != null) return all.bits(objs(be), i);
            int k = partOf[i];
            Part p = parts[k];
            return p.snap.bits(objs(be)[k], i - p.off);
        }

        @Override
        public long hashPrims(Object be) {
            Object[] o = objs(be);
            if (all != null) return all.hashPrims(o);
            long h = SnapCompiler.H0;
            for (int k = 0; k < parts.length; k++) h = mix(h, parts[k].snap.hashPrims(o[k]));
            return h;
        }

        /** the Java twin of hashPrims over a composite snapshot */
        long hashOf(long[] v) {
            if (all != null) return SnapCompiler.hashPrims(v, prim, special);
            long h = SnapCompiler.H0;
            for (Part p : parts) {
                System.arraycopy(v, p.off, p.scratch, 0, p.f.length);
                h = mix(h, SnapCompiler.hashPrims(p.scratch, p.prim, p.special));
            }
            return h;
        }

        @Override
        public boolean sameRefs(Object be, Object[] refs) {
            Object[] o = objs(be);
            if (all != null) return all.sameRefs(o, refs);
            for (int k = 0; k < parts.length; k++) {
                Part p = parts[k];
                System.arraycopy(refs, p.refOff, p.refScratch, 0, p.refSlots.length);
                if (!p.snap.sameRefs(o[k], p.refScratch)) return false;
            }
            return true;
        }

        private static long mix(long h, long x) {
            h = (h + x) * SnapCompiler.HM;
            return h ^ (h >>> 31);
        }

        /** the parts' objects for an entity, or null if one is missing or of another class than the plan's */
        Object[] resolveAll(Entity e) {
            Object[] o = new Object[parts.length];
            Path.memoOn = true;
            try {
                for (int k : order) {
                    if (parts[k].path.isEmpty()) {
                        o[k] = e;
                    } else {
                        int pk = parentPart[k];
                        Object base = pk < 0 ? e : o[pk];
                        o[k] = base == null ? null : rel[k].get(base);
                    }
                    if (o[k] == null || o[k].getClass() != parts[k].cls) return null;
                }
            } finally {
                Path.memoOn = false;
                Path.memoClear();
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ assumptions

    private static final Map<String, Method> METHODS = new HashMap<>();

    /** per assumption key: the method found for the last holder class (and its int argument, -1 none) */
    private record Called(Class<?> cls, Method m, int arg) {}

    private static final Map<String, Called> LAST_CALL = new HashMap<>();

    /** null when every assumption of the plan holds on the entity now, else the first that does not */
    /** the plan's assumptions, each with its kind and, where the key is a slot of the composite, that slot (-1: by reflection) */
    /**
     * The plan's assumptions against the composite: a key that is a slot reads the snapshot; otherwise its holder (the
     * key's path without its last step) is taken from the parts when it is one ({@code holderPart}), else resolved, and
     * the last step ({@code names}) read or called on it ({@code calls}: the method found per key, by holder class).
     */
    record Assumptions(String[] keys, byte[] kinds, int[] slots, Class<?>[] types, int[] holderPart, String[] holderPath, String[] names, Called[] calls) {}

    static final byte A_NULL = 0, A_ZERO = 1, A_LE0 = 2, A_GE0 = 3, A_EMPTY = 4, A_FALSE = 5;

    static Assumptions compileAssumptions(SleepAnalysis.MobPlan mp, Field[] f, String[] slotPath, String[] partPaths) {
        List<String> keys = new ArrayList<>();
        List<Byte> kinds = new ArrayList<>();
        for (String k : mp.nulls()) { keys.add(k); kinds.add(A_NULL); }
        for (String k : mp.zeros()) { keys.add(k); kinds.add(A_ZERO); }
        for (String k : mp.le0()) { keys.add(k); kinds.add(A_LE0); }
        for (String k : mp.ge0()) { keys.add(k); kinds.add(A_GE0); }
        for (String k : mp.empty()) { keys.add(k); kinds.add(A_EMPTY); }
        for (String k : mp.falseCalls()) { keys.add(k); kinds.add(A_FALSE); }
        int m = keys.size();
        byte[] kd = new byte[m];
        int[] slots = new int[m], holderPart = new int[m];
        String[] holderPath = new String[m], names = new String[m];
        Class<?>[] types = new Class<?>[m];
        for (int j = 0; j < m; j++) {
            kd[j] = kinds.get(j);
            slots[j] = -1;
            String key = keys.get(j);
            int dot = key.lastIndexOf('.');
            String hp = dot < 0 ? "" : key.substring(0, dot), fn = dot < 0 ? key : key.substring(dot + 1);
            holderPath[j] = hp;
            names[j] = fn;
            holderPart[j] = partPaths == null ? -1 : java.util.Arrays.asList(partPaths).indexOf(hp);
            if (kd[j] == A_FALSE) continue;
            int found = -1, count = 0;
            for (int i = 0; i < f.length; i++) {
                if (dev.lull.Names.official(f[i].getName()).equals(fn) && slotPath[i].equals(hp)) {
                    found = i;
                    count++;
                }
            }
            if (count == 1) {   // a shadowed name (two fields, one name) stays on the reflective path
                slots[j] = found;
                types[j] = f[found].getType();
            }
        }
        return new Assumptions(keys.toArray(new String[0]), kd, slots, types, holderPart, holderPath, names, new Called[m]);
    }

    /**
     * assumptionsFail on a snapshot of the composite just taken ({@code v} primitives in the snapshot encoding,
     * {@code r} the objects): keys that are slots read from it, the rest (deeper paths, method calls) by reflection.
     */
    static String assumptionsFail(Assumptions a, Entity e, long[] v, Object[] r, Object[] ctx) {
        for (int j = 0; j < a.keys.length; j++) {
            int i = a.slots[j];
            byte kind = a.kinds[j];
            String key = a.keys[j];
            boolean bad;
            if (kind == A_FALSE) {
                bad = callOn(a, j, holder(a, j, e, ctx));
            } else if (i < 0) {
                Object h = a.names[j].indexOf('[') >= 0 ? null : holder(a, j, e, ctx);
                Object x = a.names[j].indexOf('[') >= 0 ? value(e, key, false) : h == null ? null : fieldValue(h, a.names[j]);
                long nx = x instanceof Boolean b ? (b ? 1 : 0) : x instanceof Number n ? n.longValue() : x instanceof Character ch ? ch : Long.MIN_VALUE;
                bad = switch (kind) {
                    case A_NULL -> x != null;
                    case A_ZERO -> nx != 0;
                    case A_LE0 -> nx > 0;
                    case A_GE0 -> nx < 0;
                    default -> !empty(x);
                };
            } else if (kind == A_NULL || kind == A_EMPTY) {
                Object o = a.types[j].isPrimitive() ? Boolean.TRUE : r[i];
                bad = kind == A_NULL ? o != null : !empty(o);
            } else {
                Object o = r[i];
                long x = a.types[j].isPrimitive() ? numberOf(a.types[j], v[i])
                        : o instanceof Boolean bo ? (bo ? 1 : 0) : o instanceof Number nb ? nb.longValue() : o instanceof Character ch ? ch : Long.MIN_VALUE;
                bad = kind == A_ZERO ? x != 0 : kind == A_LE0 ? x > 0 : x < 0;
            }
            if (bad) return (kind == A_NULL ? "needs null " : kind == A_ZERO ? "needs zero " : kind == A_LE0 ? "needs <= 0 " : kind == A_GE0 ? "needs >= 0 "
                    : kind == A_EMPTY ? "needs empty " : "needs false ") + key;
        }
        return null;
    }

    /** the object an assumption's last step applies to: a part (no path walk) or the resolved holder path */
    private static Object holder(Assumptions a, int j, Entity e, Object[] ctx) {
        int k = a.holderPart[j];
        if (k >= 0 && ctx != null) return ctx[k];
        return a.holderPath[j].isEmpty() ? e : resolve(e, a.holderPath[j]);
    }

    /** call() with the holder given and the method cached per assumption */
    private static boolean callOn(Assumptions a, int j, Object holder) {
        if (holder == null) return true;
        Called last = a.calls[j];
        if (last == null || last.cls != holder.getClass()) {
            String name = a.names[j];
            int hash = name.indexOf('#');
            String mn = hash < 0 ? name : name.substring(0, hash);
            Method m = null;
            for (Class<?> c = holder.getClass(); c != null && c != Object.class && m == null; c = c.getSuperclass())
                for (Method x : c.getDeclaredMethods())
                    if (dev.lull.Names.official(x.getName()).equals(mn) && x.getReturnType() == boolean.class
                            && (hash < 0 ? x.getParameterCount() == 0 : x.getParameterCount() == 1 && x.getParameterTypes()[0] == int.class)) {
                        x.setAccessible(true);
                        m = x;
                        break;
                    }
            a.calls[j] = last = new Called(holder.getClass(), m, hash < 0 ? -1 : Integer.parseInt(name.substring(hash + 1)));
        }
        if (last.m == null) return true;
        try {
            return (Boolean) (last.arg < 0 ? last.m.invoke(holder) : last.m.invoke(holder, last.arg));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    /** number() of a primitive slot from its snapshot encoding */
    private static long numberOf(Class<?> t, long bits) {
        if (t == float.class) return (long) Float.intBitsToFloat((int) bits);
        if (t == double.class) return (long) Double.longBitsToDouble(bits);
        if (t.isPrimitive()) return bits;   // int, long, short, byte, char, boolean (0 / 1)
        return Long.MIN_VALUE;
    }

    static String assumptionsFail(SleepAnalysis.MobPlan mp, Entity e) {
        for (String k : mp.nulls()) if (value(e, k, true) != null) return "needs null " + k;
        for (String k : mp.zeros()) if (number(e, k) != 0) return "needs zero " + k;
        for (String k : mp.le0()) if (number(e, k) > 0) return "needs <= 0 " + k;
        for (String k : mp.ge0()) if (number(e, k) < 0) return "needs >= 0 " + k;
        for (String k : mp.empty()) if (!empty(value(e, k, false))) return "needs empty " + k;
        for (String k : mp.falseCalls()) if (call(e, k)) return "needs false " + k;
        return null;
    }

    /** the empty collections, checked every replayed tick (their content is not in the reference check) */
    static boolean stillEmpty(SleepAnalysis.MobPlan mp, Entity e) {
        for (String k : mp.empty()) if (!empty(value(e, k, false))) return false;
        return true;
    }

    private static boolean empty(Object v) {
        return v instanceof java.util.Collection<?> c ? c.isEmpty() : v instanceof Map<?, ?> m && m.isEmpty();
    }

    private static Object value(Entity e, String key, boolean nullIfMissing) {
        return resolve(e, key);   // the key is a path whose last step is the field
    }

    private static long number(Entity e, String key) {
        Object v = value(e, key, false);
        return v instanceof Boolean b ? (b ? 1 : 0) : v instanceof Number n ? n.longValue() : v instanceof Character c ? c : Long.MIN_VALUE;
    }

    /** a boolean method (name, or name#arg) of the object at the key's path */
    private static boolean call(Entity e, String key) {
        int dot = key.lastIndexOf('.');
        Object holder = dot < 0 ? e : resolve(e, key.substring(0, dot));
        String name = dot < 0 ? key : key.substring(dot + 1);
        if (holder == null) return true;
        Called last = LAST_CALL.get(key);
        if (last != null && last.cls == holder.getClass()) {
            if (last.m == null) return true;
            try {
                return (Boolean) (last.arg < 0 ? last.m.invoke(holder) : last.m.invoke(holder, last.arg));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return true;
            }
        }
        int hash = name.indexOf('#');
        String mn = hash < 0 ? name : name.substring(0, hash);
        String ck = holder.getClass().getName() + "." + name;
        Method m = METHODS.get(ck);
        if (m == null && !METHODS.containsKey(ck)) {
            for (Class<?> c = holder.getClass(); c != null && c != Object.class && m == null; c = c.getSuperclass())
                for (Method x : c.getDeclaredMethods())
                    if (dev.lull.Names.official(x.getName()).equals(mn) && x.getReturnType() == boolean.class
                            && (hash < 0 ? x.getParameterCount() == 0 : x.getParameterCount() == 1 && x.getParameterTypes()[0] == int.class)) {
                        x.setAccessible(true);
                        m = x;
                        break;
                    }
            METHODS.put(ck, m);
        }
        LAST_CALL.put(key, new Called(holder.getClass(), m, hash < 0 ? -1 : Integer.parseInt(name.substring(hash + 1))));
        if (m == null) return true;
        try {
            return (Boolean) (hash < 0 ? m.invoke(holder) : m.invoke(holder, Integer.parseInt(name.substring(hash + 1))));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    // ------------------------------------------------------------------ draws

    static final int NEXT_INT = 0, NEXT_INT_BOUND = 1, NEXT_LONG = 2, NEXT_BOOLEAN = 3, NEXT_FLOAT = 4, NEXT_DOUBLE = 5;

    /** the random being recorded (an observed entity's); null otherwise */
    static Object REC;
    /** the draws of the tick being observed: (site id << 40 | kind << 32 | bound), value bits */
    static final LongArrayList TICK = new LongArrayList();
    static final List<String> SITES = DrawSiteIds.SITES;
    private static final StackWalker WALKER = StackWalker.getInstance(java.util.Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE), 6);

    /** a draw's call site as the walker gives it cheaply: method name and bytecode index (names are interned) */
    private record SiteKey(Class<?> owner, String method, int bci) {}

    private static final Map<SiteKey, Integer> SITE_CACHE = new HashMap<>();
    /** per class: the method names it declares more than once (a site there needs the descriptor) */
    private static final Map<Class<?>, Set<String>> OVERLOADED = new HashMap<>();

    /** the first frame outside the random and these hooks: the draw's call site */
    private static StackWalker.StackFrame caller(java.util.stream.Stream<StackWalker.StackFrame> fs) {
        for (java.util.Iterator<StackWalker.StackFrame> it = fs.iterator(); it.hasNext(); ) {
            StackWalker.StackFrame f = it.next();
            Class<?> c = f.getDeclaringClass();
            if (c != MobSleep.class && c != MobSleepHooks.class && !net.minecraft.util.RandomSource.class.isAssignableFrom(c)) return f;
        }
        return null;
    }

    private static int siteId(String site) {
        return DrawSiteIds.id(site);
    }

    static long sitesGiven, sitesWalked;

    /**
     * MobSleepRandomMixin: a draw of the random being recorded. Its site (owner.method+desc@line, the analysis' key)
     * is resolved once per (class, method name, bytecode index) - line numbers and descriptors are the walker's
     * expensive part (~28 us a draw on the N100); a name declared twice in its class always takes the full path.
     * A call site the write barrier's transform numbered (MobSleepHooks.site, set for the call's duration) needs no
     * walk at all.
     */
    static void draw(int kind, int bound, long valueBits) {
        long t0 = EntitySleep.TIMING ? System.nanoTime() : 0;
        int given = MobSleepHooks.site;
        if (given >= 0) {
            sitesGiven++;
            TICK.add(((long) given << 40) | ((long) kind << 32) | (bound & 0xFFFFFFFFL));
            TICK.add(valueBits);
            if (EntitySleep.TIMING) {
                EntitySleep.S().OT[3] += System.nanoTime() - t0;
                EntitySleep.S().OT[4]++;
            }
            return;
        }
        sitesWalked++;
        StackWalker.StackFrame f = WALKER.walk(MobSleep::caller);
        long t1 = EntitySleep.TIMING ? System.nanoTime() : 0;
        int id;
        if (f == null) {
            id = siteId("?");
        } else {
            Class<?> owner = f.getDeclaringClass();
            String name = f.getMethodName();
            if (EntitySleep.TIMING) {
                EntitySleep.S().OT[5] += t1 - t0;
                EntitySleep.S().OT[6] += System.nanoTime() - t1;
            }
            Set<String> over = OVERLOADED.computeIfAbsent(owner, MobSleep::overloadedNames);
            SiteKey key = over.contains(name) ? null : new SiteKey(owner, name, f.getByteCodeIndex());
            Integer cached = key == null ? null : SITE_CACHE.get(key);
            if (cached != null) {
                id = cached;
            } else {
                id = siteId(f.getClassName().replace('.', '/') + "." + name + f.getDescriptor() + "@" + f.getLineNumber());
                if (key != null) SITE_CACHE.put(key, id);
            }
        }
        TICK.add(((long) id << 40) | ((long) kind << 32) | (bound & 0xFFFFFFFFL));
        TICK.add(valueBits);
        if (EntitySleep.TIMING) {
            EntitySleep.S().OT[3] += System.nanoTime() - t0;
            EntitySleep.S().OT[4]++;
        }
    }

    private static Set<String> overloadedNames(Class<?> c) {
        Set<String> seen = new java.util.HashSet<>(), twice = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) if (!seen.add(m.getName())) twice.add(m.getName());
        for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) if (!seen.add("<init>")) twice.add("<init>");
        return twice;
    }

    static int siteOf(long key) {
        return (int) (key >>> 40);
    }

    static int kindOf(long key) {
        return (int) (key >>> 32) & 0xFF;
    }

    static int boundOf(long key) {
        return (int) key;
    }

    /** the draw conditions of a class by call site (owner.method+desc@line): a site with two conditions is ambiguous */
    static Map<String, List<Integer>> sitesOf(List<SleepAnalysis.DrawCond> draws) {
        Map<String, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < draws.size(); i++) {
            String s = draws.get(i).site();
            m.computeIfAbsent(s.substring(0, s.lastIndexOf('#')), k -> new ArrayList<>()).add(i);
        }
        return m;
    }

    /** the draw's value as a number (ints and booleans exact, floats as their double) */
    static double asNumber(int kind, long bits) {
        return switch (kind) {
            case NEXT_FLOAT -> Float.intBitsToFloat((int) bits);
            case NEXT_DOUBLE -> Double.longBitsToDouble(bits);
            default -> bits;
        };
    }

    /** whether the condition's jump is taken for this draw value and the other side's value */
    static boolean taken(SleepAnalysis.DrawCond d, double v, double other) {
        double a = d.drawFirst() ? v : other, b = d.drawFirst() ? other : v;
        int c = Double.compare(a, b);
        if (c != 0) c = c < 0 ? -1 : 1;
        int op = d.jumpOp();
        if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE) op -= Opcodes.IF_ICMPEQ - Opcodes.IFEQ;
        return switch (op) {
            case Opcodes.IFEQ -> c == 0;
            case Opcodes.IFNE -> c != 0;
            case Opcodes.IFLT -> c < 0;
            case Opcodes.IFGE -> c >= 0;
            case Opcodes.IFGT -> c > 0;
            case Opcodes.IFLE -> c <= 0;
            default -> throw new IllegalStateException("jump " + op);
        };
    }

    /** a copy of LegacyRandomSource's generator: the same next(bits) and the same draws as BitRandomSource */
    static final class Lcg {
        long seed;

        Lcg(long seed) {
            this.seed = seed;
        }

        int next(int bits) {
            seed = seed * 25214903917L + 11L & 281474976710655L;
            return (int) (seed >> 48 - bits);
        }

        long draw(int kind, int bound) {
            switch (kind) {
                case NEXT_INT:
                    return next(32);
                case NEXT_INT_BOUND: {
                    if ((bound & bound - 1) == 0) return (int) ((long) bound * (long) next(31) >> 31);
                    int i, j;
                    do {
                        i = next(31);
                        j = i % bound;
                    } while (i - j + (bound - 1) < 0);
                    return j;
                }
                case NEXT_LONG: {
                    int i = next(32), j = next(32);
                    return ((long) i << 32) + j;
                }
                case NEXT_BOOLEAN:
                    return next(1) != 0 ? 1 : 0;
                case NEXT_FLOAT:
                    return Float.floatToRawIntBits(next(24) * 5.9604645E-8F);
                case NEXT_DOUBLE: {
                    int i = next(26), j = next(27);
                    return Double.doubleToRawLongBits((double) (((long) i << 27) + j) * 1.1102230246251565E-16);
                }
                default:
                    throw new IllegalStateException("draw kind " + kind);
            }
        }
    }

    /** the same draw on the real random (the replay): its seed advances as the tick would have advanced it */
    static void replay(net.minecraft.util.RandomSource r, int kind, int bound) {
        switch (kind) {
            case NEXT_INT -> r.nextInt();
            case NEXT_INT_BOUND -> r.nextInt(bound);
            case NEXT_LONG -> r.nextLong();
            case NEXT_BOOLEAN -> r.nextBoolean();
            case NEXT_FLOAT -> r.nextFloat();
            case NEXT_DOUBLE -> r.nextDouble();
            default -> throw new IllegalStateException("draw kind " + kind);
        }
    }

    private static final Field LEGACY_SEED = field(net.minecraft.world.level.levelgen.LegacyRandomSource.class, "seed");

    /** the AtomicLong of a LegacyRandomSource (null for other kinds of random) */
    static AtomicLong seedOf(Object random) {
        if (!(random instanceof net.minecraft.world.level.levelgen.LegacyRandomSource) || LEGACY_SEED == null) return null;
        try {
            return (AtomicLong) LEGACY_SEED.get(random);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
