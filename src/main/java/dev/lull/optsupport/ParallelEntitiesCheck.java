package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;

/**
 * -Dlab.pentities.check=true (an exactness test, not a mode for play): every run entity's parallel tick compared with the
 * vanilla one. Before the run each entity's state is taken (every field of its class chain; its random source's state;
 * collections and maps it owns, by content); the run ticks in parallel (its deferred storage moves dropped); its state is
 * taken again, the first state put back, and the entity ticked here on the render thread in the walk's order with the
 * storage moved for real - the vanilla tick, kept. The two results compared field by field (doubles by bits).
 */
final class ParallelEntitiesCheck {
    private ParallelEntitiesCheck() {}

    static long checked, mismatches;
    private static final Map<String, Long> BY_FIELD = new java.util.TreeMap<>();
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    /** the storage's callback: its moves are deferred in the parallel pass, done in the serial one (not compared) */
    private static final String SKIP = "levelCallback";

    private static Field[] fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> l = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass())
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getName().equals(SKIP) || cache(f)) continue;
                    try {
                        f.setAccessible(true);
                        l.add(f);
                    } catch (RuntimeException e) {
                        // a field we cannot reach: compared without it
                    }
                }
            return l.toArray(new Field[0]);
        });
    }

    /** our own caches on the entity (collision memos: rebuilt from the world when stale, not game state) */
    private static boolean cache(Field f) {
        return f.getType().getName().startsWith("dev.lull.") && f.getType().getSimpleName().endsWith("Memo");
    }

    /** a field's value as state: content copies of what the entity mutates in place (random state, collections, maps, arrays) */
    private static Object state(Object v, int depth) throws ReflectiveOperationException {
        if (v == null) return null;
        if (v instanceof java.util.concurrent.atomic.AtomicLong a) return a.get();
        if (v instanceof java.util.concurrent.atomic.AtomicInteger a) return a.get();
        if (v instanceof net.minecraft.util.RandomSource && depth < 2) return objectState(v, depth + 1);
        if (v instanceof net.minecraft.world.level.levelgen.MarsagliaPolarGaussian && depth < 3) return objectState(v, depth + 1);
        if (v instanceof Map<?, ?> m) return new HashMap<>(m);
        if (v instanceof Collection<?> c) return new ArrayList<>(c);
        if (v instanceof double[] a) return a.clone();
        if (v instanceof int[] a) return a.clone();
        if (v instanceof float[] a) return a.clone();
        if (v instanceof boolean[] a) return a.clone();
        if (v instanceof long[] a) return a.clone();
        return v;
    }

    /** the fields of a small owned object (a random source), as a list of states */
    private static List<Object> objectState(Object o, int depth) throws ReflectiveOperationException {
        List<Object> l = new ArrayList<>();
        l.add(o);   // identity: which object to put the state back into
        for (Field f : fields(o.getClass())) l.add(state(f.get(o), depth));
        return l;
    }

    static Object[][] snapshot(Entity[] all, int from, int to) {
        Object[][] s = new Object[to - from][];
        try {
            for (int i = from; i < to; i++) s[i - from] = snapshot(all[i]);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private static Object[] snapshot(Entity e) throws ReflectiveOperationException {
        Field[] fs = fields(e.getClass());
        Object[] v = new Object[fs.length * 2];
        for (int i = 0; i < fs.length; i++) {
            Object x = fs[i].get(e);
            v[2 * i] = x;   // the reference (put back as is)
            v[2 * i + 1] = state(x, 0);   // its content, when the entity mutates it in place
        }
        return v;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restore(Entity e, Object[] v) throws ReflectiveOperationException {
        Field[] fs = fields(e.getClass());
        for (int i = 0; i < fs.length; i++) {
            Object ref = v[2 * i], st = v[2 * i + 1];
            if (!Modifier.isFinal(fs[i].getModifiers()) || fs[i].get(e) != ref) fs[i].set(e, ref);
            putBack(ref, st);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putBack(Object ref, Object st) throws ReflectiveOperationException {
        if (ref == null || ref == st) return;
        if (ref instanceof java.util.concurrent.atomic.AtomicLong a) a.set((Long) st);
        else if (ref instanceof java.util.concurrent.atomic.AtomicInteger a) a.set((Integer) st);
        else if (st instanceof List<?> l && !l.isEmpty() && l.get(0) == ref && !(ref instanceof Collection)) {
            Field[] fs = fields(ref.getClass());
            for (int i = 0; i < fs.length; i++) {
                Object inner = l.get(i + 1);
                Object cur = fs[i].get(ref);
                if (inner instanceof List<?> il && !il.isEmpty() && il.get(0) == cur) putBack(cur, inner);
                else if (cur instanceof java.util.concurrent.atomic.AtomicLong || cur instanceof java.util.concurrent.atomic.AtomicInteger) putBack(cur, inner);
                else fs[i].set(ref, inner);
            }
        } else if (ref instanceof Map m) {   // only when changed: an immutable one is replaced, never changed in place
            if (!m.equals(st)) {
                m.clear();
                m.putAll((Map) st);
            }
        } else if (ref instanceof Collection c) {
            if (!same(new ArrayList<>(c), st)) {
                c.clear();
                c.addAll((Collection) st);
            }
        } else if (ref instanceof double[] a) System.arraycopy(st, 0, a, 0, a.length);
        else if (ref instanceof int[] a) System.arraycopy(st, 0, a, 0, a.length);
        else if (ref instanceof float[] a) System.arraycopy(st, 0, a, 0, a.length);
        else if (ref instanceof boolean[] a) System.arraycopy(st, 0, a, 0, a.length);
        else if (ref instanceof long[] a) System.arraycopy(st, 0, a, 0, a.length);
    }

    /** after the parallel pass: its results taken, the entities put back and ticked here as vanilla does; compared */
    static void verify(Entity[] all, int from, int to, Consumer<Entity> tick, Object[][] before) {
        try {
            Object[][] par = new Object[to - from][];
            for (int i = from; i < to; i++) par[i - from] = snapshot(all[i]);
            for (int i = from; i < to; i++) restore(all[i], before[i - from]);
            for (int i = from; i < to; i++) tick.accept(all[i]);
            for (int i = from; i < to; i++) {
                Entity e = all[i];
                Object[] ser = snapshot(e), p = par[i - from];
                Field[] fs = fields(e.getClass());
                boolean ok = true;
                for (int k = 0; k < fs.length; k++)
                    if (!same(ser[2 * k + 1], p[2 * k + 1])) {
                        ok = false;
                        String key = e.getClass().getSimpleName() + "." + fs[k].getName();
                        if (BY_FIELD.merge(key, 1L, Long::sum) <= 3)
                            System.out.println("[sleep/opt] parallel entities MISMATCH " + key + ": parallel " + show(p[2 * k + 1]) + ", vanilla " + show(ser[2 * k + 1]));
                    }
                checked++;
                if (!ok) mismatches++;
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static String show(Object o) {
        String s = o instanceof double[] a ? java.util.Arrays.toString(a) : String.valueOf(o);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Double x && b instanceof Double y) return Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(y);
        if (a instanceof Float x && b instanceof Float y) return Float.floatToRawIntBits(x) == Float.floatToRawIntBits(y);
        if (a instanceof net.minecraft.world.phys.Vec3 x && b instanceof net.minecraft.world.phys.Vec3 y)
            return same(x.x, y.x) && same(x.y, y.y) && same(x.z, y.z);
        if (a instanceof net.minecraft.world.phys.AABB x && b instanceof net.minecraft.world.phys.AABB y)
            return same(x.minX, y.minX) && same(x.minY, y.minY) && same(x.minZ, y.minZ) && same(x.maxX, y.maxX) && same(x.maxY, y.maxY) && same(x.maxZ, y.maxZ);
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof double[] x && b instanceof double[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof int[] x && b instanceof int[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof float[] x && b instanceof float[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof boolean[] x && b instanceof boolean[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof long[] x && b instanceof long[] y) return java.util.Arrays.equals(x, y);
        return a.equals(b);
    }

    static String report() {
        return String.format(java.util.Locale.ROOT, "; checked %d, MISMATCHES %d %s", checked, mismatches, BY_FIELD);
    }
}
