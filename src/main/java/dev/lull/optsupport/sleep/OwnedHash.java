package dev.lull.optsupport.sleep;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ObservedModSleep: a hash of everything a block entity owns - its fields below BlockEntity, followed into the objects
 * they hold (an energy storage, a tank, a handler, a timer object) three levels deep, collections and arrays by their
 * elements. Values by value (primitives, strings, enums), shared registry objects (blocks, items, fluids, states) and
 * links out of the block entity (the level, other block entities, entities, functions) by identity or not at all. An
 * observed-only sleeper is idle only while this does not change, and wakes on any change while it sleeps.
 */
final class OwnedHash {
    private OwnedHash() {}

    private static final int DEPTH = 3, MAX_ELEMENTS = 256;
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> SEEN = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final Class<?> BE = net.minecraft.world.level.block.entity.BlockEntity.class;

    static long of(Object be) {
        IdentityHashMap<Object, Boolean> seen = SEEN.get();
        seen.clear();
        seen.put(be, Boolean.TRUE);
        long h = 17;
        for (Field f : fields(be.getClass(), true)) h = h * 1_000_003L + value(get(f, be), 1, seen);
        return h;
    }

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** the instance fields of the class chain (below BlockEntity for a block entity), accessible ones only */
    private static Field[] fields(Class<?> c, boolean be) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> l = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class && x != BE; x = x.getSuperclass())
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    try {
                        f.setAccessible(true);
                        l.add(f);
                    } catch (RuntimeException e) {
                        // a module-closed field: left out (a class the mods own is open; JDK internals are not followed)
                    }
                }
            return l.toArray(new Field[0]);
        });
    }

    private static long value(Object v, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (v == null) return 0;
        if (v instanceof Number n) return v instanceof Double || v instanceof Float ? Double.doubleToLongBits(n.doubleValue()) : n.longValue();
        if (v instanceof Boolean b) return b ? 1231 : 1237;
        if (v instanceof Character ch) return ch;
        if (v instanceof CharSequence || v instanceof Enum<?> || v instanceof java.util.UUID) return v.hashCode();
        Class<?> c = v.getClass();
        // links out of the block entity, and shared immutable objects: identity at most
        if (v instanceof net.minecraft.world.level.Level || v instanceof net.minecraft.world.entity.Entity || BE.isInstance(v)
                || v instanceof net.minecraft.world.level.block.state.StateHolder<?, ?> || v instanceof net.minecraft.world.level.block.Block
                || v instanceof net.minecraft.world.item.Item || v instanceof net.minecraft.world.level.material.Fluid
                || v instanceof net.minecraft.core.Holder<?> || v instanceof net.minecraft.resources.ResourceLocation
                || v instanceof net.minecraft.core.Vec3i || v instanceof Class<?> || v instanceof Thread || c.isSynthetic() || c.isHidden())
            return System.identityHashCode(v);
        if (seen.put(v, Boolean.TRUE) != null || depth > DEPTH) return 31;
        if (v instanceof net.minecraft.world.item.ItemStack st)   // by value: item, count, components
            return st.isEmpty() ? 0 : System.identityHashCode(st.getItem()) * 31L + st.getCount() * 7L + st.getComponents().hashCode();
        if (c.isArray()) {
            int n = Math.min(Array.getLength(v), MAX_ELEMENTS * 16);
            long h = n;
            for (int i = 0; i < n; i++) h = h * 31 + value(Array.get(v, i), depth + 1, seen);
            return h;
        }
        if (v instanceof Map<?, ?> m) {
            long h = m.size();
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ >= MAX_ELEMENTS) break;
                h = h * 31 + value(e.getKey(), depth + 1, seen) * 7 + value(e.getValue(), depth + 1, seen);
            }
            return h;
        }
        if (v instanceof Collection<?> col) {
            long h = col.size();
            int i = 0;
            for (Object o : col) {
                if (i++ >= MAX_ELEMENTS) break;
                h = h * 31 + value(o, depth + 1, seen);
            }
            return h;
        }
        if (c.getName().startsWith("java.") || c.getName().startsWith("jdk.") || c.getName().startsWith("sun.")) return System.identityHashCode(v);
        long h = c.hashCode();
        for (Field f : fields(c, false)) h = h * 1_000_003L + value(get(f, v), depth + 1, seen);
        return h;
    }
}
