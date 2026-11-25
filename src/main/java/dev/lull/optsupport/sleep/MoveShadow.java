package dev.lull.optsupport.sleep;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.world.entity.Entity;

/**
 * Measurement only (-Dsleep.moveShadow=true, MoveShadowMixin): could Entity.move be skipped for a mob whose inputs did
 * not change? Per call: every instance field before and after (SnapCompiler, by value). Fields that differ between one
 * call's "before" and the next are counted (what changes between moves). With a key = every field but the excluded
 * ones (-Dsleep.moveShadow.exclude=tickCount,random,...), a call whose key equals the last call's is a hit: the last
 * call's "after" (non-excluded fields) predicts this one's, and the real move checks it - a wrong prediction is
 * reported with its fields. Nothing is skipped. Server thread, non-player living entities.
 */
public final class MoveShadow {
    public static final boolean ON = Boolean.getBoolean("sleep.moveShadow");
    private static final Set<String> EXCLUDE = Set.of(System.getProperty("sleep.moveShadow.exclude", "tickCount").split(","));

    private static final class K {
        final Field[] f;
        final SnapCompiler.Snapper snap;
        final boolean[] skip;
        final long[] changed;
        long calls, hits, right, wrong, hitNanos, allNanos;
        final Map<String, long[]> wrongFields = new TreeMap<>();

        K(Class<?> c) {
            List<Field> fl = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (Field x : k.getDeclaredFields()) {
                    if (dev.lull.Names.officialMember(x.getName()).equals("sleep$w")) continue;   // the write barrier's watcher (WriteBarrier), not state
                    if (Modifier.isStatic(x.getModifiers())) continue;
                    try {
                        x.setAccessible(true);
                        fl.add(x);
                    } catch (RuntimeException e) {
                        // left out
                    }
                }
            f = fl.toArray(new Field[0]);
            skip = new boolean[f.length];
            for (int i = 0; i < f.length; i++) skip[i] = EXCLUDE.contains(f[i].getName());
            snap = SnapCompiler.compile(fl, true, new boolean[f.length]);
            changed = new long[f.length];
        }
    }

    private static final class E {
        long[] lastBefore, lastAfter, before;
        long t0;
    }

    private static final Map<Class<?>, K> KINDS = new HashMap<>();
    private static final Int2ObjectOpenHashMap<E> BY_ID = new Int2ObjectOpenHashMap<>();
    private static Thread thread;
    private static int depth;
    private static long lastPrint = -1;

    private MoveShadow() {}

    private static boolean mine(Entity e) {
        if (e.level().isClientSide || e instanceof net.minecraft.world.entity.player.Player || !(e instanceof net.minecraft.world.entity.LivingEntity)) return false;
        if (thread == null) thread = Thread.currentThread();
        return Thread.currentThread() == thread;
    }

    public static void before(Entity e) {
        if (!mine(e) || depth++ > 0) return;
        K k = KINDS.computeIfAbsent(e.getClass(), K::new);
        E s = BY_ID.computeIfAbsent(e.getId(), i -> new E());
        if (s.before == null) s.before = new long[k.f.length];
        k.snap.snap(e, s.before);
        s.t0 = System.nanoTime();
    }

    public static void after(Entity e) {
        if (!mine(e) || --depth > 0) return;
        long dt = System.nanoTime() - BY_ID.get(e.getId()).t0;
        K k = KINDS.get(e.getClass());
        E s = BY_ID.get(e.getId());
        long[] after = new long[k.f.length];
        k.snap.snap(e, after);
        k.calls++;
        k.allNanos += dt;
        if (s.lastBefore != null) {
            boolean same = true;
            for (int i = 0; i < after.length; i++) {
                if (s.before[i] != s.lastBefore[i]) {
                    k.changed[i]++;
                    if (!k.skip[i]) same = false;
                }
            }
            if (same) {
                k.hits++;
                k.hitNanos += dt;
                boolean ok = true;
                for (int i = 0; i < after.length; i++) {
                    if (k.skip[i]) continue;
                    if (after[i] != s.lastAfter[i]) {
                        ok = false;
                        k.wrongFields.computeIfAbsent(k.f[i].getDeclaringClass().getSimpleName() + "." + k.f[i].getName(), x -> new long[1])[0]++;
                    }
                }
                // an excluded field the move itself changed would not be replayed: count it as wrong
                for (int i = 0; i < after.length; i++)
                    if (k.skip[i] && after[i] != s.before[i]) {
                        ok = false;
                        k.wrongFields.computeIfAbsent("(move writes excluded) " + k.f[i].getName(), x -> new long[1])[0]++;
                    }
                if (ok) k.right++;
                else k.wrong++;
            }
        }
        long[] t = s.lastBefore;
        s.lastBefore = s.before;
        s.before = t;
        s.lastAfter = after;
        long gt = e.level().getGameTime();
        if (gt % 600 == 0 && gt != lastPrint) {
            lastPrint = gt;
            print();
        }
    }

    private static void print() {
        StringBuilder sb = new StringBuilder("[sleep/moveshadow] exclude " + EXCLUDE + "; per class: calls, key hits %, right, WRONG, move time on hits % of all move time");
        for (var en : KINDS.entrySet()) {
            K k = en.getValue();
            if (k.calls == 0) continue;
            sb.append(String.format(Locale.ROOT, "%n   %-14s %8d  %5.1f %%  %8d  %6d  %5.1f %%", en.getKey().getSimpleName(), k.calls, 100.0 * k.hits / k.calls, k.right, k.wrong,
                    100.0 * k.hitNanos / Math.max(1, k.allNanos)));
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < k.f.length; i++) if (k.changed[i] > 0) idx.add(i);
            idx.sort((a, b) -> Long.compare(k.changed[b], k.changed[a]));
            sb.append("\n      fields changing between moves:");
            for (int j = 0; j < Math.min(16, idx.size()); j++)
                sb.append(String.format(Locale.ROOT, " %s %.0f%%%s", k.f[idx.get(j)].getName(), 100.0 * k.changed[idx.get(j)] / k.calls, k.skip[idx.get(j)] ? "(x)" : ""));
            if (!k.wrongFields.isEmpty()) sb.append("\n      WRONG in: ").append(k.wrongFields.entrySet().stream().limit(12).map(x -> x.getKey() + " " + x.getValue()[0]).toList());
            java.util.Arrays.fill(k.changed, 0);
            k.calls = k.hits = k.right = k.wrong = k.hitNanos = k.allNanos = 0;
            k.wrongFields.clear();
        }
        System.out.println(sb);
    }
}
