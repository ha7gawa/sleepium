package dev.lull.sleepium.render;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import sun.misc.Unsafe;

/**
 * EntityCache (as 1.20.1's RenderCache StateHash): a hash of an object's whole instance state - every field of its
 * class chain; vectors, boxes, positions, synched data, item stacks, arrays and collections by value, the random
 * generator by seed, other references by identity. Tick counters and the world are left out (probes cover what ages
 * by time). Fields learned not to change what is drawn leave the hash too: a re-recording after a state change that
 * draws the same counts a confirmation for every field that changed; CONFIRM of them and the field is ignored for its
 * class; a change alone that draws something else makes the field relevant for good.
 */
final class StateHash {
    private StateHash() {}

    private static final Unsafe U;

    static {
        Unsafe u = null;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            System.out.println("[sleepium] EntityCache: no Unsafe (" + e + ")");
        }
        U = u;
    }

    /** MCP and SRG names: Entity.ticksExisted, Entity.world, TileEntity.world */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("ticksExisted", "field_70173_aa", "world", "field_70170_p", "field_145850_b"));
    private static final int CONFIRM = Integer.getInteger("sleep.rcache.learnConfirm", 16);
    static long learned;

    private static final class Plan {
        final long[] offsets;
        final char[] kinds;
        final String[] names;
        final boolean[] ignored, relevant;
        final int[] confirmed;

        Plan(long[] offsets, char[] kinds, String[] names) {
            this.offsets = offsets;
            this.kinds = kinds;
            this.names = names;
            int n = offsets.length;
            ignored = new boolean[n];
            relevant = new boolean[n];
            confirmed = new int[n];
        }
    }

    private static final Map<Class<?>, Plan> PLANS = new HashMap<>();

    private static Plan plan(Class<?> c) {
        Plan p = PLANS.get(c);
        if (p != null) return p;
        List<Long> offs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        StringBuilder ks = new StringBuilder();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || SKIP.contains(f.getName())) continue;
                offs.add(U.objectFieldOffset(f));
                names.add(k.getSimpleName() + "." + f.getName());
                Class<?> t = f.getType();
                ks.append(t == int.class ? 'I' : t == long.class ? 'J' : t == float.class ? 'F' : t == double.class ? 'D' : t == boolean.class ? 'Z'
                        : t == byte.class ? 'B' : t == short.class ? 'S' : t == char.class ? 'C' : 'L');
            }
        }
        long[] o = new long[offs.size()];
        for (int i = 0; i < o.length; i++) o[i] = offs.get(i);
        PLANS.put(c, p = new Plan(o, ks.toString().toCharArray(), names.toArray(new String[0])));
        return p;
    }

    static boolean available() {
        return U != null;
    }

    private static long one(Plan p, int i, Object o) {
        long off = p.offsets[i];
        switch (p.kinds[i]) {
            case 'I': return U.getInt(o, off);
            case 'J': return U.getLong(o, off);
            case 'F': return Float.floatToRawIntBits(U.getFloat(o, off));
            case 'D': return Double.doubleToRawLongBits(U.getDouble(o, off));
            case 'Z': return U.getBoolean(o, off) ? 1 : 0;
            case 'B': return U.getByte(o, off);
            case 'S': return U.getShort(o, off);
            case 'C': return U.getChar(o, off);
            default: return fp(U.getObject(o, off));
        }
    }

    static long of(Object o) {
        Plan p = plan(o.getClass());
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < p.offsets.length; i++) {
            if (p.ignored[i]) continue;
            long x = one(p, i, o);
            h = (h ^ x) * 0x100000001B3L + (x >>> 29) + i;
        }
        return h;
    }

    static long[] fields(Object o) {
        Plan p = plan(o.getClass());
        long[] r = new long[p.offsets.length];
        for (int i = 0; i < r.length; i++) r[i] = one(p, i, o);
        return r;
    }

    /** a re-recording after a state change drew the same: the fields that changed count a confirmation */
    static void unchanged(Object o, long[] before, long[] now) {
        Plan p = plan(o.getClass());
        if (before.length != now.length) return;
        for (int i = 0; i < now.length; i++) {
            if (before[i] == now[i] || p.ignored[i] || p.relevant[i]) continue;
            if (++p.confirmed[i] >= CONFIRM) {
                p.ignored[i] = true;
                learned++;
                System.out.println("[sleepium] render cache: " + o.getClass().getSimpleName() + " " + p.names[i] + " changed " + CONFIRM
                        + " times without changing what is drawn: left out of its state hash");
            }
        }
    }

    /** a re-recording after a state change drew something else: a field that changed alone is relevant */
    static void changedOutput(Object o, long[] before, long[] now) {
        Plan p = plan(o.getClass());
        if (before.length != now.length) return;
        int one = -1;
        for (int i = 0; i < now.length; i++) {
            if (before[i] == now[i]) continue;
            if (one >= 0) return;
            one = i;
        }
        if (one >= 0) p.relevant[one] = true;
    }

    // ------------------------------------------------------------------ value fingerprints (1.20.1's EntityFp / FieldDiff, 1.12 classes)

    private static long mix(long a, long b) {
        return (a ^ b) * 0x9E3779B97F4A7C15L + (b >>> 17);
    }

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    private static Field randSeed, dmEntries, dmValue, dmDirty;
    private static boolean dmTried;

    static long fp(Object r) {
        if (r == null) return 0;
        if (r instanceof Vec3d) {
            Vec3d v = (Vec3d) r;
            return mix(mix(bits(v.x), bits(v.y)), bits(v.z));
        }
        if (r instanceof AxisAlignedBB) {
            AxisAlignedBB b = (AxisAlignedBB) r;
            return mix(mix(mix(mix(mix(bits(b.minX), bits(b.minY)), bits(b.minZ)), bits(b.maxX)), bits(b.maxY)), bits(b.maxZ));
        }
        if (r instanceof BlockPos && !(r instanceof BlockPos.MutableBlockPos)) return ((BlockPos) r).toLong() * 0x9E3779B97F4A7C15L + 1;
        if (r instanceof EntityDataManager) return synched((EntityDataManager) r);
        if (r instanceof java.util.Random) return seed((java.util.Random) r);
        if (r instanceof Boolean || r instanceof Number || r instanceof String || r instanceof Enum<?>) return r.hashCode() * 31L + 3;
        if (r instanceof NonNullList<?>) {
            NonNullList<?> list = (NonNullList<?>) r;
            long h = System.identityHashCode(list);
            for (int i = 0, n = list.size(); i < n; i++) h = h * 31 + stack(list.get(i));
            return h;
        }
        if (r instanceof ItemStack) return stack(r);
        if (r instanceof int[]) return System.identityHashCode(r) * 31L + Arrays.hashCode((int[]) r);
        if (r instanceof long[]) return System.identityHashCode(r) * 31L + Arrays.hashCode((long[]) r);
        if (r instanceof boolean[]) return System.identityHashCode(r) * 31L + Arrays.hashCode((boolean[]) r);
        if (r instanceof byte[]) return System.identityHashCode(r) * 31L + Arrays.hashCode((byte[]) r);
        if (r instanceof float[]) return System.identityHashCode(r) * 31L + Arrays.hashCode((float[]) r);
        if (r instanceof double[]) return System.identityHashCode(r) * 31L + Arrays.hashCode((double[]) r);
        if (r instanceof Object[]) {
            long h = System.identityHashCode(r);
            for (Object e : (Object[]) r) h = h * 31 + (e instanceof ItemStack ? stack(e) : System.identityHashCode(e));
            return h;
        }
        if (r instanceof Map<?, ?>) return System.identityHashCode(r) * 31L + ((Map<?, ?>) r).size() * 7919L;
        if (r instanceof java.util.Collection<?>) return System.identityHashCode(r) * 31L + ((java.util.Collection<?>) r).size() * 7919L;
        return System.identityHashCode(r);
    }

    private static long stack(Object e) {
        if (!(e instanceof ItemStack)) return 0;
        ItemStack s = (ItemStack) e;
        if (s.isEmpty()) return 0;
        return System.identityHashCode(s.getItem()) * 1_000_003L + s.getCount() * 7919L + s.getMetadata() * 131L + System.identityHashCode(s)
                + java.util.Objects.hashCode(s.getTagCompound()) * 31L;
    }

    private static long seed(java.util.Random r) {
        try {
            if (randSeed == null) {
                randSeed = java.util.Random.class.getDeclaredField("seed");
                randSeed.setAccessible(true);
            }
            return ((java.util.concurrent.atomic.AtomicLong) randSeed.get(r)).get() * 31 + 7;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return System.identityHashCode(r);
        }
    }

    /** synched data: every entry's value (by value when immutable, else identity) and dirty flag */
    private static long synched(EntityDataManager d) {
        try {
            if (!dmTried) {
                dmTried = true;
                // by type (the same in development and production): the manager's one Map, an entry's Object and boolean
                dmEntries = byType(EntityDataManager.class, Map.class);
                Class<?> de = EntityDataManager.DataEntry.class;
                dmValue = byType(de, Object.class);
                dmDirty = byType(de, boolean.class);
            }
            if (dmEntries == null || dmValue == null || dmDirty == null) return System.identityHashCode(d);
            Map<?, ?> m = (Map<?, ?>) dmEntries.get(d);
            long h = 17;
            for (Object en : m.values()) {
                Object v = dmValue.get(en);
                long x = v == null ? 0 : (v instanceof Boolean || v instanceof Number || v instanceof String || v instanceof Enum<?>) ? v.hashCode() * 31L
                        : v instanceof ItemStack ? stack(v) : System.identityHashCode(v);
                h = mix(h, x * 2 + (dmDirty.getBoolean(en) ? 1 : 0));
            }
            return h;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return System.identityHashCode(d);
        }
    }

    private static Field byType(Class<?> c, Class<?> type) {
        Field found = null;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
            if (found != null) return null;   // not one: unknown layout
            found = f;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static Field field(Class<?> c, String mcp, String srg) {
        for (String n : new String[] {mcp, srg}) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** entities: the tick counter moved for the probe (renderers that age by it) */
    static void addTicks(Object o, int n) {
        if (o instanceof Entity) ((Entity) o).ticksExisted += n;
    }
}
