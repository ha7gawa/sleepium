package dev.lull.optsupport;

import dev.lull.optsupport.sleep.EntityFp;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RenderCache: a hash of an object's whole instance state (every field of its class chain; references by value where
 * that is how they change — see EntityFp.fp — otherwise by identity). Fields listed in SKIP change every tick without
 * describing what is drawn (tick counters are probed instead: RenderCache renders once with another tickCount).
 *
 * <p>-Dlab.rcache.learnFields: fields learned not to change what is drawn leave the hash too. RenderCache reports each
 * re-recording caused by a state change: when the output is the same as before, every field that changed counts a
 * confirmation; when the output differs and one field alone changed, that field is relevant for good. A field with
 * CONFIRM confirmations that is not relevant is ignored for its class (Create's SmartBlockEntity.lazyTickCounter: it
 * changes every tick, its renderers never read it).
 */
final class StateHash {
    private static final Set<String> SKIP = Set.of("tickCount", "level", "levelCallback");

    private record Plan(VarHandle[] handles, char[] kinds, String[] names, boolean[] ignored, int[] confirmed, boolean[] relevant) {}

    static final boolean LEARN = Boolean.getBoolean("lab.rcache.learnFields");
    private static final int CONFIRM = Integer.getInteger("lab.rcache.learnConfirm", 16);
    static long learned;

    private static final ClassValue<Plan> PLANS = new ClassValue<>() {
        @Override
        protected Plan computeValue(Class<?> c) {
            List<VarHandle> hs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            StringBuilder ks = new StringBuilder();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                MethodHandles.Lookup l;
                try {
                    l = MethodHandles.privateLookupIn(k, MethodHandles.lookup());
                } catch (IllegalAccessException | RuntimeException e) {
                    continue;
                }
                for (Field f : k.getDeclaredFields()) {
                    if (dev.lull.Names.officialMember(f.getName()).equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (Modifier.isStatic(f.getModifiers()) || SKIP.contains(dev.lull.Names.officialMember(f.getName()))) continue;
                    try {
                        hs.add(l.unreflectVarHandle(f));
                    } catch (IllegalAccessException | RuntimeException e) {
                        continue;
                    }
                    names.add(k.getSimpleName() + "." + f.getName());
                    Class<?> t = f.getType();
                    ks.append(t == int.class ? 'I' : t == long.class ? 'J' : t == float.class ? 'F' : t == double.class ? 'D' : t == boolean.class ? 'Z'
                            : t == byte.class ? 'B' : t == short.class ? 'S' : t == char.class ? 'C' : 'L');
                }
            }
            int n = hs.size();
            return new Plan(hs.toArray(new VarHandle[0]), ks.toString().toCharArray(), names.toArray(new String[0]), new boolean[n], new int[n], new boolean[n]);
        }
    };

    private StateHash() {}

    /** Per-field hashes (for -Dlab.rcache.why: which fields change). */
    static long[] fields(Object o) {
        Plan p = PLANS.get(o.getClass());
        long[] r = new long[p.handles.length];
        for (int i = 0; i < r.length; i++) r[i] = one(p, i, o);
        return r;
    }

    /** The re-recording after a state change from {@code before} to {@code now} drew the same output. */
    static void unchanged(Object o, long[] before, long[] now) {
        Plan p = PLANS.get(o.getClass());
        if (before.length != now.length) return;
        for (int i = 0; i < now.length; i++) {
            if (before[i] == now[i] || p.ignored[i] || p.relevant[i]) continue;
            if (++p.confirmed[i] >= CONFIRM) {
                p.ignored[i] = true;
                learned++;
                System.out.println("[sleep/opt] render cache: " + o.getClass().getSimpleName() + " " + p.names[i] + " changed " + CONFIRM
                        + " times without changing what is drawn: left out of its state hash");
            }
        }
    }

    /** The re-recording after a state change drew something else: a field that changed alone is relevant. */
    static void changedOutput(Object o, long[] before, long[] now) {
        Plan p = PLANS.get(o.getClass());
        if (before.length != now.length) return;
        int one = -1;
        for (int i = 0; i < now.length; i++) {
            if (before[i] == now[i]) continue;
            if (one >= 0) return;   // several: which one made the difference is not known
            one = i;
        }
        if (one >= 0) {
            p.relevant[one] = true;
            if (p.ignored[one]) {   // (cannot happen while it is left out: its change alone does not change the hash)
                p.ignored[one] = false;
                learned--;
            }
        }
    }

    static String name(Object o, int i) {
        return PLANS.get(o.getClass()).names[i];
    }

    private static long one(Plan p, int i, Object o) {
        VarHandle[] hs = p.handles;
        return switch (p.kinds[i]) {
            case 'I' -> (int) hs[i].get(o);
            case 'J' -> (long) hs[i].get(o);
            case 'F' -> Float.floatToRawIntBits((float) hs[i].get(o));
            case 'D' -> Double.doubleToRawLongBits((double) hs[i].get(o));
            case 'Z' -> (boolean) hs[i].get(o) ? 1 : 0;
            case 'B' -> (byte) hs[i].get(o);
            case 'S' -> (short) hs[i].get(o);
            case 'C' -> (char) hs[i].get(o);
            default -> EntityFp.fp(hs[i].get(o));
        };
    }

    static long of(Object o) {
        Plan p = PLANS.get(o.getClass());
        long h = 0x9E3779B97F4A7C15L;
        VarHandle[] hs = p.handles;
        char[] ks = p.kinds;
        boolean[] ign = p.ignored;
        for (int i = 0; i < hs.length; i++) {
            if (ign[i]) continue;
            long x = switch (ks[i]) {
                case 'I' -> (int) hs[i].get(o);
                case 'J' -> (long) hs[i].get(o);
                case 'F' -> Float.floatToRawIntBits((float) hs[i].get(o));
                case 'D' -> Double.doubleToRawLongBits((double) hs[i].get(o));
                case 'Z' -> (boolean) hs[i].get(o) ? 1 : 0;
                case 'B' -> (byte) hs[i].get(o);
                case 'S' -> (short) hs[i].get(o);
                case 'C' -> (char) hs[i].get(o);
                default -> EntityFp.fp(hs[i].get(o));
            };
            h = (h ^ x) * 0x100000001B3L + (x >>> 29) + i;
        }
        return h;
    }
}
