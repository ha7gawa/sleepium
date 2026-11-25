package dev.lull.optsupport.sleep;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/**
 * Measurement only (-Dsleep.componentShadow=true, ComponentShadowMixin): which fields of a mob's owned components change
 * from one tick to the next - the look / move / jump / body rotation controls, the navigation, the sensing, both goal
 * selectors and every goal in them. The state a whole-tick mob sleep would have to watch or model beyond the entity's
 * own fields. Per mob class and component field: the share of ticks it changed, all ticks and quiet ticks (the
 * entity's position, rotations and motion unchanged). Printed every 600 game ticks.
 */
public final class ComponentShadow {
    public static final boolean ON = Boolean.getBoolean("sleep.componentShadow");
    private static final String[] COMPONENTS = {"lookControl", "moveControl", "jumpControl", "bodyRotationControl", "navigation", "sensing", "goalSelector", "targetSelector"};
    private static final Map<Class<?>, List<Field>> FIELDS = new HashMap<>();
    private static final Map<Integer, Map<String, Long>> LAST = new HashMap<>();
    private static final Map<String, long[]> CHANGES = new TreeMap<>();   // class/component.field -> {changed, changedQuiet}
    private static final Map<String, long[]> TICKS = new TreeMap<>();     // class -> {ticks, quiet}
    private static final Map<Integer, Long> LAST_SELF = new HashMap<>();
    private static long lastPrint = -1;

    private ComponentShadow() {}

    private static List<Field> fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> l = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass())
                for (Field f : x.getDeclaredFields()) {
                    if (dev.lull.Names.officialMember(f.getName()).equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        l.add(f);
                    } catch (RuntimeException e) {
                        // left out
                    }
                }
            return l;
        });
    }

    private static Field mobField(String name) {
        for (Field f : fields(Mob.class)) if (dev.lull.Names.officialMember(f.getName()).equals(name)) return f;
        return null;
    }

    private static void snap(String prefix, Object o, Map<String, Long> out, IdentityHashMap<Object, Boolean> seen, Object mob) {
        if (o == null || seen.put(o, true) != null) return;
        for (Field f : fields(o.getClass())) {
            Object v;
            try {
                v = f.get(o);
            } catch (IllegalAccessException e) {
                continue;
            }
            String key = prefix + "." + f.getName();
            if (v == mob) continue;   // the component's mob
            if (f.getType().isPrimitive()) out.put(key, v instanceof Double d ? Double.doubleToLongBits(d) : v instanceof Float x ? Float.floatToIntBits(x) : v instanceof Boolean b ? (b ? 1 : 0) : ((Number) (v instanceof Character ch ? (int) ch : v)).longValue());
            else out.put(key, EntityFp.fp(v));
            if (v instanceof java.util.Collection<?> col && key.endsWith("availableGoals")) {
                for (Object g : col) if (g instanceof WrappedGoal wg) snap(prefix + "/" + wg.getGoal().getClass().getSimpleName(), wg, out, seen, mob);
            }
            if (v instanceof net.minecraft.world.entity.ai.goal.Goal) snap(prefix, v, out, seen, mob);
        }
    }

    public static void before(Mob m) {
        if (m.level().isClientSide) return;
        Map<String, Long> now = new HashMap<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (String c : COMPONENTS) {
            Field f = mobField(c);
            if (f == null) continue;
            try {
                snap(c, f.get(m), now, seen, m);
            } catch (IllegalAccessException e) {
                // left out
            }
        }
        long self = java.util.Objects.hash(m.getX(), m.getY(), m.getZ(), m.getYRot(), m.getXRot(), m.getYHeadRot(), m.getDeltaMovement().x, m.getDeltaMovement().y, m.getDeltaMovement().z);
        String cls = m.getClass().getSimpleName();
        Map<String, Long> last = LAST.put(m.getId(), now);
        Long lastSelf = LAST_SELF.put(m.getId(), self);
        if (last != null) {
            boolean quiet = lastSelf != null && lastSelf == self;
            long[] t = TICKS.computeIfAbsent(cls, k -> new long[2]);
            t[0]++;
            if (quiet) t[1]++;
            for (var e : now.entrySet()) {
                Long was = last.get(e.getKey());
                if (was != null && !was.equals(e.getValue())) {
                    long[] c = CHANGES.computeIfAbsent(cls + " " + e.getKey(), k -> new long[2]);
                    c[0]++;
                    if (quiet) c[1]++;
                }
            }
        }
        long gt = m.level().getGameTime();
        if (gt % 600 == 0 && gt != lastPrint) {
            lastPrint = gt;
            print();
        }
    }

    private static void print() {
        StringBuilder sb = new StringBuilder("[sleep/components] component fields changing between ticks (all ticks % / quiet ticks %), per class:");
        String cur = null;
        List<Map.Entry<String, long[]>> l = new ArrayList<>(CHANGES.entrySet());
        l.sort((a, b) -> {
            String ca = a.getKey().split(" ")[0], cb = b.getKey().split(" ")[0];
            return ca.equals(cb) ? Long.compare(b.getValue()[0], a.getValue()[0]) : ca.compareTo(cb);
        });
        int shown = 0;
        for (var e : l) {
            String cls = e.getKey().split(" ")[0];
            long[] t = TICKS.get(cls);
            if (!cls.equals(cur)) {
                cur = cls;
                shown = 0;
                sb.append(String.format(Locale.ROOT, "%n   %s: %d ticks, %.0f %% quiet", cls, t[0], 100.0 * t[1] / Math.max(1, t[0])));
            }
            if (shown++ >= 30) continue;
            sb.append(String.format(Locale.ROOT, "%n      %5.1f %% / %5.1f %%  %s", 100.0 * e.getValue()[0] / t[0], 100.0 * e.getValue()[1] / Math.max(1, t[1]), e.getKey().substring(cls.length() + 1)));
        }
        System.out.println(sb);
        CHANGES.clear();
        TICKS.clear();
    }
}
