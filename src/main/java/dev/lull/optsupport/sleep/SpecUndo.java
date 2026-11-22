package dev.lull.optsupport.sleep;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;

/**
 * SpecTicks (lab, notes/99): an entity's own state before a tick, to put back when its speculative parallel tick must
 * be undone. The state is the graph of objects the entity owns: the entity's fields, and recursively the objects of
 * its kind it holds (goals, controls, navigation and path, synched data items, attributes, combat tracker, brain, its
 * random source, walk animation, effects...) - by class: net.minecraft.world.entity.* objects that are not entities or
 * shared registry objects, synched data, paths, random sources, effect instances, the engine's own per-entity objects;
 * and the content of the mutable collections, maps and arrays they hold. Everything else (vectors, boxes, block states,
 * other entities, the level) is a value or not the entity's: kept by reference. Per class one generated snapper
 * (SnapCompiler) copies the non-final fields; final references are followed only.
 *
 * <p>The first state of an entity class is found by walking the graph (generic: an identity set, a class lookup per
 * reference); it is kept as the class' template - which part sits in which field of which part, which fields must stay
 * null (a part appearing there would be new), the contents and their part elements. Later states follow the template
 * (no lookups, no allocation); any difference (a class, a part appearing, a content's part count) takes the generic walk.
 */
public final class SpecUndo {
    private SpecUndo() {}

    static final byte LEAF = 0, PART = 1, CONTENT = 2;

    /** a part class: its non-final fields (copied) and its final reference fields (followed) */
    static final class Shape {
        final Field[] f;
        final SnapCompiler.Snapper sn;
        final boolean[] prim;
        final Field[] fin;
        final SnapCompiler.Snapper finSn;

        Shape(Class<?> c) {
            List<Field> fs = new ArrayList<>(), fins = new ArrayList<>();
            for (Class<?> x = c; x != null && x != Object.class; x = x.getSuperclass())
                for (Field fl : x.getDeclaredFields()) {
                    int m = fl.getModifiers();
                    if (Modifier.isStatic(m) || fl.getName().equals(WriteBarrier.FIELD)) continue;
                    if (Modifier.isFinal(m)) {
                        if (!fl.getType().isPrimitive()) fins.add(fl);
                    } else fs.add(fl);
                }
            f = fs.toArray(new Field[0]);
            prim = new boolean[f.length];
            for (int i = 0; i < f.length; i++) prim[i] = f[i].getType().isPrimitive();
            sn = SnapCompiler.compile(fs);
            fin = fins.toArray(new Field[0]);
            finSn = fins.isEmpty() ? null : SnapCompiler.compile(fins);
        }
    }

    private static final ClassValue<Shape> SHAPES = new ClassValue<>() {
        @Override
        protected Shape computeValue(Class<?> c) {
            return new Shape(c);
        }
    };

    private static final ClassValue<Byte> KIND = new ClassValue<>() {
        @Override
        protected Byte computeValue(Class<?> c) {
            return kind(c);
        }
    };

    static byte kind(Class<?> c) {
        if (c.isArray()) return CONTENT;
        if (Entity.class.isAssignableFrom(c) || c.isEnum() || c.isRecord() || c.isHidden() || c.isSynthetic()) return LEAF;
        String n = c.getName();
        if (n.equals("java.util.concurrent.atomic.AtomicLong") || n.equals("java.util.concurrent.atomic.AtomicInteger")) return CONTENT;
        if (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c)) {
            if (n.contains("Immutable") || n.contains("Unmodifiable") || n.contains("Singleton") || n.contains("Empty") || n.startsWith("java.util.ImmutableCollections"))
                return LEAF;
            return n.startsWith("java.util.") || n.startsWith("it.unimi.dsi.fastutil.") ? CONTENT : LEAF;
        }
        if (n.startsWith("net.minecraft.world.entity.")) {
            // shared registry objects and types of the entity package: not the entity's
            if (n.equals("net.minecraft.world.entity.EntityType") || n.startsWith("net.minecraft.world.entity.ai.attributes.Attribute") && !n.contains("Instance") && !n.contains("Map")
                    || n.contains("MemoryModuleType") || n.contains("SensorType") || n.startsWith("net.minecraft.world.entity.schedule.") || n.endsWith(".Activity")
                    || n.equals("net.minecraft.world.entity.EntityDimensions") || n.contains("ai.attributes.AttributeSupplier"))
                return LEAF;
            return PART;
        }
        if (n.startsWith("net.minecraft.network.syncher.SynchedEntityData") || n.equals("net.minecraft.world.level.pathfinder.Path")
                || n.equals("net.minecraft.world.damagesource.CombatTracker") || n.equals("net.minecraft.world.effect.MobEffectInstance")
                || n.startsWith("net.minecraft.world.level.levelgen.") && (n.endsWith("RandomSource") || n.endsWith("Gaussian") || n.endsWith("Xoroshiro128PlusPlus")))
            return PART;
        if (n.startsWith("dev.lull.") && !n.endsWith("Memo") && !n.contains("Memo$")) return PART;
        return LEAF;
    }

    static byte kindOf(Object v) {
        return v == null ? LEAF : KIND.get(v.getClass());
    }

    // fast copies of JDK collections the goals hold (their fields by Unsafe offsets: java.util is not open to us)
    static final sun.misc.Unsafe U = SnapCompiler.U;
    static final Class<?> REGULAR_ENUM_SET, ENUM_MAP;
    static final long RES_ELEMENTS, EM_VALS, EM_SIZE;

    static {
        Class<?> res = null, em = null;
        long a = -1, b = -1, c = -1;
        try {
            res = Class.forName("java.util.RegularEnumSet");
            em = java.util.EnumMap.class;
            if (U != null) {
                a = U.objectFieldOffset(res.getDeclaredField("elements"));
                b = U.objectFieldOffset(em.getDeclaredField("vals"));
                c = U.objectFieldOffset(em.getDeclaredField("size"));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.out.println("[sleep/spec] no fast enum set / map copies: " + ex);
        }
        REGULAR_ENUM_SET = a >= 0 ? res : null;
        ENUM_MAP = b >= 0 ? em : null;
        RES_ELEMENTS = a;
        EM_VALS = b;
        EM_SIZE = c;
    }

    // a content's representation: K_LONG (cLong: an atomic's value, a regular enum set's bits), K_MAP (cBuf: keys and
    // values interleaved), K_SEQ (cBuf: elements; an enum map's values array as it is, size in cLong), K_PRIM (a copy)
    static final byte K_LONG = 0, K_MAP = 1, K_SEQ = 2, K_PRIM = 3, K_ENUMMAP = 4;

    // slot codes (a reference slot of a part / an element of a content, as the template expects it)
    static final int IGNORE = -1, MUST_NULL = -2, CBASE = -10;   // >= 0: that part; <= CBASE: content CBASE - code

    /** one entity's state, reused (one per thread or per walk slot) */
    public static final class Snap {
        Object[] parts = new Object[64];
        Shape[] shapes = new Shape[64];
        long[][] prims = new long[64][];
        Object[][] refs = new Object[64][];
        Object[][] fins = new Object[64][];
        int np;
        Object[] cObj = new Object[32];
        Object[] cBuf = new Object[32];   // Object[] (maps: keys and values interleaved; collections; object arrays) or a primitive array copy
        int[] cLen = new int[32];
        long[] cLong = new long[32];
        byte[] cKind = new byte[32];
        int nc;
        // the walk's structure (generic walks only: what a template is made from)
        int[][] codes = new int[64][], finCodes = new int[64][];
        int[][] elemCodes = new int[32][];
        final IdentityHashMap<Object, Integer> seen = new IdentityHashMap<>(128);
        public int parts() { return np; }
        public int contents() { return nc; }
    }

    static final int MAX_PARTS = 512, MAX_DEPTH = 8;
    public static long overflows, generic, templated, mismatches;

    /** a class' state layout (from a generic walk) */
    static final class Template {
        final Class<?>[] cls;
        final Shape[] shapes;
        final int[][] codes, finCodes;
        final int[][] refSlots, finSlots;   // per part: the slots whose code is not IGNORE
        final Class<?>[] cCls;
        final int[][] elemCodes;
        final boolean[] cHasParts;
        final int[] cLen0;
        final Class<?>[][] cLeafCls;   // per content: element classes seen as values (an element of another class is checked)
        final int np, nc;

        Template(Snap s) {
            np = s.np;
            nc = s.nc;
            cls = new Class<?>[np];
            shapes = java.util.Arrays.copyOf(s.shapes, np);
            codes = new int[np][];
            finCodes = new int[np][];
            refSlots = new int[np][];
            finSlots = new int[np][];
            for (int i = 0; i < np; i++) {
                cls[i] = s.parts[i].getClass();
                codes[i] = s.codes[i] == null ? new int[0] : java.util.Arrays.copyOf(s.codes[i], shapes[i].f.length);
                finCodes[i] = s.finCodes[i] == null ? new int[0] : java.util.Arrays.copyOf(s.finCodes[i], shapes[i].fin.length);
                refSlots[i] = active(codes[i]);
                finSlots[i] = active(finCodes[i]);
            }
            cCls = new Class<?>[nc];
            elemCodes = new int[nc][];
            cHasParts = new boolean[nc];
            cLen0 = new int[nc];
            cLeafCls = new Class<?>[nc][];
            for (int c = 0; c < nc; c++) {
                cCls[c] = s.cObj[c].getClass();
                cLen0[c] = s.cLen[c];
                int[] ec = s.elemCodes[c];
                elemCodes[c] = ec == null ? new int[0] : java.util.Arrays.copyOf(ec, s.cLen[c]);
                for (int x : elemCodes[c]) if (x != IGNORE) cHasParts[c] = true;
                java.util.LinkedHashSet<Class<?>> leaf = new java.util.LinkedHashSet<>();
                if (s.cBuf[c] instanceof Object[] b)
                    for (int j = 0; j < s.cLen[c]; j++) if (b[j] != null && kindOf(b[j]) == LEAF) leaf.add(b[j].getClass());
                cLeafCls[c] = leaf.toArray(new Class<?>[0]);
            }
        }

        private static int[] active(int[] codes) {
            int n = 0;
            for (int c : codes) if (c != IGNORE) n++;
            int[] a = new int[n];
            n = 0;
            for (int k = 0; k < codes.length; k++) if (codes[k] != IGNORE) a[n++] = k;
            return a;
        }
    }

    private static final ClassValue<Template[]> TEMPLATES = new ClassValue<>() {
        @Override
        protected Template[] computeValue(Class<?> c) {
            return new Template[8];
        }
    };

    /** the entity's state into s */
    public static void take(Entity e, Snap s) {
        long t0 = System.nanoTime();
        Template[] h = TEMPLATES.get(e.getClass());
        // the class' templates, the last one that fitted first
        for (int k = 0; k < h.length; k++) {
            Template t = h[k];
            if (t == null) break;
            if (takeTemplate(t, e, s)) {
                templated++;
                if (k > 0) {   // (move to front)
                    System.arraycopy(h, 0, h, 1, k);
                    h[0] = t;
                }
                nsTemplate += System.nanoTime() - t0;
                return;
            }
        }
        if (h[0] != null) mismatches++;
        generic++;
        s.np = 0;
        s.nc = 0;
        s.seen.clear();
        visit(e, s, 0);
        // learned: a new variant of the class' layout (the oldest one dropped when all slots are taken)
        System.arraycopy(h, 0, h, 1, h.length - 1);
        h[0] = new Template(s);
        nsGeneric += System.nanoTime() - t0;
    }

    public static long nsTemplate, nsGeneric;

    // ------------------------------------------------------------------ the generic walk

    private static int visit(Object o, Snap s, int depth) {
        Integer known = s.seen.get(o);
        if (known != null) return IGNORE;   // (a back reference: the mob held by its goals)
        if (s.np >= MAX_PARTS || depth > MAX_DEPTH) {
            overflows++;
            return IGNORE;
        }
        Shape sh = SHAPES.get(o.getClass());
        int i = s.np++;
        s.seen.put(o, i);
        grow(s, i + 1);
        s.parts[i] = o;
        s.shapes[i] = sh;
        int n = sh.f.length;
        long[] p = s.prims[i];
        Object[] r = s.refs[i];
        if (p == null || p.length < n) s.prims[i] = p = new long[Math.max(n, 8)];
        if (r == null || r.length < n) s.refs[i] = r = new Object[Math.max(n, 8)];
        if (n > 0) sh.sn.both(o, p, r);
        int[] cd = s.codes[i];
        if (cd == null || cd.length < n) s.codes[i] = cd = new int[Math.max(n, 8)];
        for (int k = 0; k < n; k++) cd[k] = sh.prim[k] ? IGNORE : follow(r[k], s, depth);
        int m = sh.fin.length;
        int[] fc = s.finCodes[i];
        if (fc == null || fc.length < m) s.finCodes[i] = fc = new int[Math.max(m, 4)];
        if (m > 0) {
            Object[] fb = s.fins[i];
            if (fb == null || fb.length < m) s.fins[i] = fb = new Object[Math.max(m, 4)];
            sh.finSn.refs(o, fb);
            for (int k = 0; k < m; k++) fc[k] = follow(fb[k], s, depth);
        }
        return i;
    }

    private static void grow(Snap s, int need) {
        if (need <= s.parts.length) return;
        int len = Math.max(need, s.parts.length * 2);
        s.parts = java.util.Arrays.copyOf(s.parts, len);
        s.shapes = java.util.Arrays.copyOf(s.shapes, len);
        s.prims = java.util.Arrays.copyOf(s.prims, len);
        s.refs = java.util.Arrays.copyOf(s.refs, len);
        s.fins = java.util.Arrays.copyOf(s.fins, len);
        s.codes = java.util.Arrays.copyOf(s.codes, len);
        s.finCodes = java.util.Arrays.copyOf(s.finCodes, len);
    }

    /** a reference met in the walk: its code (IGNORE for values and back references, MUST_NULL for null) */
    private static int follow(Object v, Snap s, int depth) {
        if (v == null) return MUST_NULL;
        byte k = kindOf(v);
        if (k == PART) return visit(v, s, depth + 1);
        if (k == CONTENT) return contentGeneric(v, s, depth);
        return IGNORE;
    }

    private static int contentGeneric(Object v, Snap s, int depth) {
        if (s.seen.containsKey(v)) return IGNORE;
        int c = s.nc++;
        s.seen.put(v, CBASE - c);
        growC(s, c + 1);
        s.cObj[c] = v;
        copyContent(s, c, v);
        int len = s.cLen[c];
        byte kd = s.cKind[c];
        if (kd == K_MAP || kd == K_SEQ || kd == K_ENUMMAP) {
            int[] ec = s.elemCodes[c];
            if (ec == null || ec.length < len) s.elemCodes[c] = ec = new int[Math.max(len, 4)];
            Object[] b = (Object[]) s.cBuf[c];
            boolean map = kd == K_MAP;
            for (int j = 0; j < len; j++) ec[j] = map && (j & 1) == 0 ? IGNORE : follow(b[j], s, depth);   // (map keys: values, not followed)
            for (int j = 0; j < len; j++) if (ec[j] == MUST_NULL) ec[j] = IGNORE;
        } else s.elemCodes[c] = null;
        return CBASE - c;
    }

    private static void growC(Snap s, int need) {
        if (need <= s.cObj.length) return;
        int len = Math.max(need, s.cObj.length * 2);
        s.cObj = java.util.Arrays.copyOf(s.cObj, len);
        s.cBuf = java.util.Arrays.copyOf(s.cBuf, len);
        s.cLen = java.util.Arrays.copyOf(s.cLen, len);
        s.cLong = java.util.Arrays.copyOf(s.cLong, len);
        s.cKind = java.util.Arrays.copyOf(s.cKind, len);
        s.elemCodes = java.util.Arrays.copyOf(s.elemCodes, len);
    }

    /** a content's copy into its reused buffer (length into cLen) */
    private static void copyContent(Snap s, int c, Object v) {
        Class<?> vc = v.getClass();
        if (vc == REGULAR_ENUM_SET) {
            s.cLong[c] = U.getLong(v, RES_ELEMENTS);
            s.cLen[c] = 0;
            s.cKind[c] = K_LONG;
            return;
        }
        if (vc == ENUM_MAP) {
            Object[] vals = (Object[]) U.getObject(v, EM_VALS);
            Object[] b = buf(s, c, vals.length);
            System.arraycopy(vals, 0, b, 0, vals.length);
            s.cLen[c] = vals.length;
            s.cLong[c] = U.getInt(v, EM_SIZE);
            s.cKind[c] = K_ENUMMAP;
            return;
        }
        s.cKind[c] = v instanceof Map ? K_MAP : v instanceof Collection || v instanceof Object[] ? K_SEQ : v.getClass().isArray() ? K_PRIM : K_LONG;
        if (v instanceof java.util.concurrent.atomic.AtomicLong a) {
            s.cLong[c] = a.get();
            s.cLen[c] = 0;
            return;
        }
        if (v instanceof java.util.concurrent.atomic.AtomicInteger a) {
            s.cLong[c] = a.get();
            s.cLen[c] = 0;
            return;
        }
        if (v instanceof Map<?, ?> m) {
            int n = m.size() * 2;
            Object[] b = buf(s, c, n);
            int q = 0;
            if (n > 0) for (Map.Entry<?, ?> en : m.entrySet()) {
                b[q++] = en.getKey();
                b[q++] = en.getValue();
            }
            s.cLen[c] = q;
            return;
        }
        if (v instanceof Collection<?> col) {
            int n = col.size();
            Object[] b = buf(s, c, n);
            int q = 0;
            if (n > 0) for (Object x : col) b[q++] = x;
            s.cLen[c] = q;
            return;
        }
        if (v instanceof Object[] a) {
            Object[] b = buf(s, c, a.length);
            System.arraycopy(a, 0, b, 0, a.length);
            s.cLen[c] = a.length;
            return;
        }
        int n = java.lang.reflect.Array.getLength(v);
        Object b = s.cBuf[c];
        if (b == null || b.getClass() != v.getClass() || java.lang.reflect.Array.getLength(b) != n) s.cBuf[c] = b = java.lang.reflect.Array.newInstance(v.getClass().getComponentType(), n);
        System.arraycopy(v, 0, b, 0, n);
        s.cLen[c] = n;
    }

    private static Object[] buf(Snap s, int c, int n) {
        if (s.cBuf[c] instanceof Object[] ob && ob.length >= n) return ob;
        Object[] nb = new Object[Math.max(n, 4)];
        s.cBuf[c] = nb;
        return nb;
    }

    // ------------------------------------------------------------------ the template walk

    private static boolean takeTemplate(Template t, Entity e, Snap s) {
        if (e.getClass() != t.cls[0]) return false;
        grow(s, t.np);
        growC(s, t.nc);
        s.parts[0] = e;
        for (int i = 0; i < t.np; i++) {
            Object o = s.parts[i];
            Shape sh = t.shapes[i];
            s.shapes[i] = sh;
            int n = sh.f.length;
            long[] p = s.prims[i];
            Object[] r = s.refs[i];
            if (p == null || p.length < n) s.prims[i] = p = new long[Math.max(n, 8)];
            if (r == null || r.length < n) s.refs[i] = r = new Object[Math.max(n, 8)];
            if (n > 0) sh.sn.both(o, p, r);
            int[] cd = t.codes[i];
            for (int k : t.refSlots[i]) if (!resolve(t, s, cd[k], r[k])) return false;
            int[] fs = t.finSlots[i];
            if (fs.length > 0) {
                Object[] fb = s.fins[i];
                int m = sh.fin.length;
                if (fb == null || fb.length < m) s.fins[i] = fb = new Object[Math.max(m, 4)];
                sh.finSn.refs(o, fb);
                int[] fc = t.finCodes[i];
                for (int k : fs) if (!resolve(t, s, fc[k], fb[k])) return false;
            }
        }
        s.np = t.np;
        s.nc = t.nc;
        return true;
    }

    private static boolean resolve(Template t, Snap s, int code, Object v) {
        if (code == MUST_NULL) return v == null;
        if (code >= 0) {
            if (v == null || v.getClass() != t.cls[code]) return false;
            s.parts[code] = v;
            return true;
        }
        int c = CBASE - code;
        if (v == null || v.getClass() != t.cCls[c]) return false;
        s.cObj[c] = v;
        copyContent(s, c, v);
        int len = s.cLen[c];
        int[] ec = t.elemCodes[c];
        if (t.cHasParts[c]) {
            if (len != t.cLen0[c]) return false;
            Object[] b = (Object[]) s.cBuf[c];
            for (int j = 0; j < ec.length; j++) if (ec[j] != IGNORE && !resolve(t, s, ec[j], b[j])) return false;
        } else if (len > 0 && s.cBuf[c] instanceof Object[] b) {
            // elements the template saw none of (or only values): none may be a part or a content now
            boolean map = s.cKind[c] == K_MAP;
            Class<?>[] lc = t.cLeafCls[c];
            for (int j = map ? 1 : 0; j < len; j += map ? 2 : 1) {
                Object x = b[j];
                if (x == null) continue;
                Class<?> xc = x.getClass();
                boolean known = false;
                for (Class<?> k : lc) if (k == xc) { known = true; break; }
                if (!known && kindOf(x) != LEAF) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ for SpecTx

    static Shape shape(Class<?> c) {
        return SHAPES.get(c);
    }

    /** content c of s: v's copy (SpecTx: a collection's first mutation in a tick) */
    static void addContent(Snap s, int c, Object v) {
        growC(s, c + 1);
        s.cObj[c] = v;
        copyContent(s, c, v);
    }

    /** per class: the offset of its outer instance field (this$0), -1 none */
    private static final ClassValue<long[]> OUTER = new ClassValue<>() {
        @Override
        protected long[] computeValue(Class<?> c) {
            for (Class<?> x = c; x != null && x != Object.class; x = x.getSuperclass())
                for (java.lang.reflect.Field f : x.getDeclaredFields())
                    if (!Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("this$") && U != null) return new long[] {U.objectFieldOffset(f)};
            return new long[] {-1};
        }
    };
    private static final ClassValue<Boolean> DIRECT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c) || c == java.util.concurrent.atomic.AtomicLong.class
                    || c == java.util.concurrent.atomic.AtomicInteger.class || c.isArray()) && OUTER.get(c)[0] < 0;
        }
    };

    /**
     * The collection whose content a mutation changes: the object itself, or for a view / iterator (an inner class
     * instance) the collection or map it belongs to (its outer instance, followed while there is one); null if neither.
     */
    static Object backing(Object c) {
        if (DIRECT.get(c.getClass())) return c;   // (a collection itself, not a view: the common case)
        Object best = c instanceof Collection || c instanceof Map || c instanceof java.util.concurrent.atomic.AtomicLong
                || c instanceof java.util.concurrent.atomic.AtomicInteger || c.getClass().isArray() ? c : null;
        Object x = c;
        for (int depth = 0; depth < 4; depth++) {
            long off = OUTER.get(x.getClass())[0];
            if (off < 0) break;
            Object o = U.getObject(x, off);
            if (o == null) break;
            if (o instanceof Collection || o instanceof Map) best = o;
            x = o;
        }
        return best;
    }

    // ------------------------------------------------------------------ putting it back

    public static long restoredParts, restoredContents;

    /** the state taken into s put back (parts whose fields all still hold the taken values are left alone) */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void restore(Snap s) {
        for (int i = 0; i < s.np; i++) {
            Shape sh = s.shapes[i];
            Object o = s.parts[i];
            long[] p = s.prims[i];
            Object[] r = s.refs[i];
            if (sh.f.length == 0 || sh.sn.same(o, p, r)) continue;
            restoredParts++;
            for (int k = 0; k < sh.f.length; k++) sh.sn.put(o, k, p[k], r[k]);
        }
        restoreContents(s);
    }

    /** the contents taken into s (cObj / cBuf / cLen / cLong, [0, nc)) put back */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void restoreContents(Snap s) {
        for (int c = 0; c < s.nc; c++) {
            Object v = s.cObj[c];
            int len = s.cLen[c];
            Class<?> vc = v.getClass();
            if (vc == REGULAR_ENUM_SET) {
                if (U.getLong(v, RES_ELEMENTS) != s.cLong[c]) {
                    restoredContents++;
                    U.putLong(v, RES_ELEMENTS, s.cLong[c]);
                }
                continue;
            }
            if (vc == ENUM_MAP) {
                Object[] vals = (Object[]) U.getObject(v, EM_VALS), b = (Object[]) s.cBuf[c];
                boolean same = U.getInt(v, EM_SIZE) == (int) s.cLong[c];
                for (int j = 0; same && j < len; j++) same = vals[j] == b[j];
                if (!same) {
                    restoredContents++;
                    System.arraycopy(b, 0, vals, 0, len);
                    U.putInt(v, EM_SIZE, (int) s.cLong[c]);
                }
                continue;
            }
            if (v instanceof java.util.concurrent.atomic.AtomicLong a) {
                a.set(s.cLong[c]);
                continue;
            }
            if (v instanceof java.util.concurrent.atomic.AtomicInteger a) {
                a.set((int) s.cLong[c]);
                continue;
            }
            if (v instanceof Map m) {
                Object[] kv = (Object[]) s.cBuf[c];
                boolean same = m.size() * 2 == len;
                for (int j = 0; same && j < len; j += 2) same = m.containsKey(kv[j]) && m.get(kv[j]) == kv[j + 1];
                if (!same) {
                    restoredContents++;
                    m.clear();
                    for (int j = 0; j < len; j += 2) m.put(kv[j], kv[j + 1]);
                }
            } else if (v instanceof Collection col) {
                Object[] a = (Object[]) s.cBuf[c];
                boolean same = col.size() == len;
                if (same) {
                    int j = 0;
                    for (Object x : col) if (x != a[j++]) { same = false; break; }
                }
                if (!same) {
                    restoredContents++;
                    col.clear();
                    for (int j = 0; j < len; j++) col.add(a[j]);
                }
            } else System.arraycopy(s.cBuf[c], 0, v, 0, len);
        }
    }

    // ------------------------------------------------------------------ comparison (the verify mode)

    /**
     * Two states of one entity taken after two runs of the same tick: the same part graph (classes in order), equal
     * primitive bits, references the same object or equal values (a new but equal Vec3), parts and contents compared in
     * order. Null when equal, else what differs first.
     */
    public static String diff(Snap a, Snap b) {
        if (a.np != b.np) return "parts " + a.np + " != " + b.np + firstClassDiff(a, b);
        for (int i = 0; i < a.np; i++) {
            if (a.shapes[i] != b.shapes[i]) return "part " + i + " class " + a.parts[i].getClass().getSimpleName() + " != " + b.parts[i].getClass().getSimpleName();
            Shape sh = a.shapes[i];
            for (int k = 0; k < sh.f.length; k++) {
                if (sh.prim[k]) {
                    if (a.prims[i][k] != b.prims[i][k]) return sh.f[k].getDeclaringClass().getSimpleName() + "." + sh.f[k].getName() + " (part " + i + ")";
                } else {
                    Object x = a.refs[i][k], y = b.refs[i][k];
                    if (!sameValue(x, y)) return sh.f[k].getDeclaringClass().getSimpleName() + "." + sh.f[k].getName() + " " + x + " != " + y;
                }
            }
        }
        if (a.nc != b.nc) return "contents " + a.nc + " != " + b.nc;
        for (int c = 0; c < a.nc; c++) {
            if (a.cLen[c] != b.cLen[c] || a.cLong[c] != b.cLong[c]) return "content " + c + " of " + a.cObj[c].getClass().getSimpleName();
            Object x = a.cBuf[c], y = b.cBuf[c];
            if (x instanceof Object[] xa && y instanceof Object[] ya) {
                for (int j = 0; j < a.cLen[c]; j++) if (!sameValue(xa[j], ya[j])) return "content " + c + " of " + a.cObj[c].getClass().getSimpleName() + " element " + j;
            } else if (x != null && !java.util.Objects.deepEquals(x, y)) return "content " + c + " of " + a.cObj[c].getClass().getSimpleName();
        }
        return null;
    }

    private static boolean sameValue(Object x, Object y) {
        if (x == y) return true;
        if (x == null || y == null) return false;
        byte k = kindOf(x);
        if (k == PART || k == CONTENT) return true;   // (compared as parts / contents in their own order)
        return x.equals(y);
    }

    private static String firstClassDiff(Snap a, Snap b) {
        for (int i = 0; i < Math.min(a.np, b.np); i++)
            if (a.shapes[i] != b.shapes[i]) return " (first at " + i + ": " + a.parts[i].getClass().getSimpleName() + " / " + b.parts[i].getClass().getSimpleName() + ")";
        return "";
    }

    /** the classes of a state's parts and contents (for the report: what an entity's state is made of) */
    public static String describe(Snap s) {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (int i = 0; i < s.np; i++) m.merge(s.parts[i].getClass().getSimpleName(), 1, Integer::sum);
        for (int i = 0; i < s.nc; i++) m.merge("[" + s.cObj[i].getClass().getSimpleName() + "]", 1, Integer::sum);
        int fields = 0;
        for (int i = 0; i < s.np; i++) fields += s.shapes[i].f.length;
        return s.np + " parts, " + s.nc + " contents, " + fields + " fields: " + m;
    }

    public static String stats() {
        return String.format(java.util.Locale.ROOT, "takes: template %d (%.0f ns), generic %d (%.0f ns; no template fitted %d), overflows %d", templated, nsTemplate / (double) Math.max(1, templated),
                generic, nsGeneric / (double) Math.max(1, generic), mismatches, overflows);
    }
}
