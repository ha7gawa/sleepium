package dev.lull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * "Did the object change in memory?" for any block-entity class, without knowing the class.
 *
 * <p>Per class, once: every instance field declared below {@link BlockEntity} (the subclass chain, vanilla or modded).
 * Per tick: a snapshot of each field as a long. Primitives are their bits; references are the identity of the object
 * (effectively its address); a {@code NonNullList<ItemStack>} / {@code ItemStack} goes one level deeper
 * (item identity and count), because inventories change in place. Comparing the snapshots before and after a tick
 * shows exactly which private fields a tick touched, e.g. a furnace's cooking progress.
 */
public final class FieldDiff {
    /** Field layout of one block-entity class. */
    public static final class Layout {
        public final Field[] fields;
        public final String[] names;

        Layout(List<Field> fs) {
            fields = fs.toArray(new Field[0]);
            names = new String[fields.length];
            for (int i = 0; i < fields.length; i++) names[i] = fields[i].getDeclaringClass().getSimpleName() + "." + fields[i].getName();
        }
    }

    private static final Map<Class<?>, Layout> LAYOUTS = new HashMap<>();
    static String failure;   // first reflection failure, reported once

    private FieldDiff() {}

    public static Layout layout(Class<?> c) {
        return LAYOUTS.computeIfAbsent(c, k -> {
            List<Field> fs = new ArrayList<>();
            for (Class<?> x = k; x != null && x != BlockEntity.class && x != Object.class; x = x.getSuperclass()) {
                for (Field f : x.getDeclaredFields()) {
                    if (dev.lull.Names.officialMember(f.getName()).equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        fs.add(f);
                    } catch (RuntimeException e) {
                        if (failure == null) failure = f + ": " + e;
                    }
                }
            }
            return new Layout(fs);
        });
    }

    public static long[] snapshot(Object o, Layout l, long[] into) {
        long[] v = into != null && into.length == l.fields.length ? into : new long[l.fields.length];
        for (int i = 0; i < l.fields.length; i++) {
            try {
                v[i] = value(l.fields[i], o);
            } catch (IllegalAccessException e) {
                v[i] = 0;
            }
        }
        return v;
    }

    private static long value(Field f, Object o) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) return f.getInt(o);
        if (t == long.class) return f.getLong(o);
        if (t == boolean.class) return f.getBoolean(o) ? 1 : 0;
        if (t == short.class) return f.getShort(o);
        if (t == byte.class) return f.getByte(o);
        if (t == char.class) return f.getChar(o);
        if (t == float.class) return Float.floatToRawIntBits(f.getFloat(o));
        if (t == double.class) return Double.doubleToRawLongBits(f.getDouble(o));
        return fingerprint(f.get(o));
    }

    /**
     * Fingerprint of a referenced object: identity (effectively its address), plus content for the containers that
     * change in place: NonNullList / ItemStack (item and count) and maps / collections (size and hash).
     * Called from the compiled snapshot routines of the sleep engine; allocation-free for lists (index loop).
     */
    public static long fingerprint(Object r) {
        if (r == null) return 0;
        if (r instanceof NonNullList<?> list) {
            long h = System.identityHashCode(list);
            for (int i = 0, n = list.size(); i < n; i++) h = h * 31 + stack(list.get(i));
            return h;
        }
        if (r instanceof ItemStack s) return stack(s);
        // arrays by content: element writes (a campfire's cooking progress) must show up
        if (r instanceof int[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof long[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof boolean[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof byte[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof short[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof char[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof float[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof double[] a) return System.identityHashCode(a) * 31L + java.util.Arrays.hashCode(a);
        if (r instanceof Object[] a) {
            long h = System.identityHashCode(a);
            for (Object e : a) h = h * 31 + (e instanceof ItemStack st ? stack(st) : System.identityHashCode(e));
            return h;
        }
        // collections and maps are snapshotted by content (size and hash), so hidden bookkeeping such as a
        // furnace's recipesUsed map shows up as a change
        if (r instanceof java.util.Map<?, ?> m) return System.identityHashCode(m) * 31L + m.size() * 7919L + m.hashCode();
        if (r instanceof java.util.Collection<?> c) return System.identityHashCode(c) * 31L + c.size() * 7919L + c.hashCode();
        return System.identityHashCode(r);   // reference identity: replaced object => changed
    }

    private static long stack(Object e) {
        if (!(e instanceof ItemStack s) || s.isEmpty()) return 0;
        // components too: they can be changed in place (stack.set(...)) without replacing the stack
        return System.identityHashCode(s.getItem()) * 1_000_003L + s.getCount() * 7919L + System.identityHashCode(s) + s.getComponents().hashCode() * 31L;
    }
}
