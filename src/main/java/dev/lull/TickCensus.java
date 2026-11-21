package dev.lull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * Measurement only ({@code -Dsleep.tickCensus=true}, needs {@code -Dlab.opt.WriteBarrier=true} and entity sleep off):
 * what one server entity tick touches beyond itself - the question before parallel server entity ticks (notes/89).
 *
 * <p>Per root tick (ServerLevel.tickNonPassenger: the entity and its passengers are one unit) it records:
 * writes into another unit's parts (the write barrier: every server entity's parts are watched by an {@link Owner}),
 * draws from the level's shared random (the level's RandomSource is wrapped by a counting delegate - the same
 * sequence), block writes (Level.setBlock), entity queries and whether they saw another unit, effects (sounds,
 * particles, game events, entity events, level events) and spawns. A tick with none of them is self-contained; a tick
 * that only read other units is "reads others". Per entity type: shares of ticks and of tick time. Printed every
 * {@code sleep.tickCensus.every} root ticks (default 50 000) as "[sleep/census]".
 */
public final class TickCensus {
    public static final boolean ON = Boolean.getBoolean("sleep.tickCensus");
    static final int EVERY = Integer.getInteger("sleep.tickCensus.every", 50_000);

    private TickCensus() {}

    /** a watched part's owner (the write barrier's watcher object) */
    public static final class Owner {
        final Entity e;

        Owner(Entity e) {
            this.e = e;
        }
    }

    // kinds, in the report's order
    static final int CROSS = 0, RAND = 1, BLOCK = 2, QUERY = 3, SEEN = 4, EFFECT = 5, SPAWN = 6, KINDS = 7;
    static final String[] NAMES = {"writes others", "level random", "sets blocks", "queries", "sees others", "effects", "spawns"};

    static final class Row {
        long ticks, ns, selfTicks, selfNs, readOnlyTicks, readOnlyNs;
        final long[] kind = new long[KINDS], kindNs = new long[KINDS];
    }

    private static final Map<String, Row> ROWS = new HashMap<>();
    private static final Map<String, Long> CROSS_FIELDS = new HashMap<>();
    private static Entity cur;
    private static final boolean[] f = new boolean[KINDS];
    private static long t0, total, randDraws, blockWrites, queries;

    // ------------------------------------------------------------------ the tick

    public static void start(Entity e) {
        if (cur != null) return;   // (nested: a passenger tick is part of its vehicle's unit)
        if (wrapped != e.level()) wrapRandom(e.level());
        watchParts(e);
        for (Entity p : e.getIndirectPassengers()) watchParts(p);
        cur = e;
        tickDraws = 0;
        long gt = e.level().getGameTime();
        if (gt != lastGameTime) {   // a new server tick: the previous one's prediction tally
            if (drawUnitsThisTick > 0) { serverTicks++; if (!missThisTick) serverTicksAllHit++; }
            lastGameTime = gt;
            missThisTick = false;
            drawUnitsThisTick = 0;
        }
        java.util.Arrays.fill(f, false);
        t0 = System.nanoTime();
    }

    public static void end(Entity e) {
        if (cur != e) return;
        long ns = System.nanoTime() - t0;
        cur = null;
        // the shared random's draw count: the same as this unit's last tick? (prediction by last tick)
        Integer last = LAST_DRAWS.put(e.getId(), tickDraws);
        if (tickDraws > 0 || (last != null && last > 0)) {
            drawUnits++;
            drawUnitsThisTick++;
            boolean hit = last != null && last == tickDraws;
            if (hit) drawHits++;
            if (missThisTick) drawAfterMiss++;
            if (!hit) missThisTick = true;
            DRAW_COUNTS.merge(tickDraws, 1L, Long::sum);
        }
        Row r = ROWS.computeIfAbsent(e.getType().toShortString(), k -> new Row());
        r.ticks++;
        r.ns += ns;
        boolean any = false, writes = false;
        for (int k = 0; k < KINDS; k++) {
            if (!f[k]) continue;
            r.kind[k]++;
            r.kindNs[k] += ns;
            if (k != QUERY && k != SEEN) writes = true;
            any |= k != QUERY;
        }
        if (!any) {
            r.selfTicks++;
            r.selfNs += ns;
        } else if (!writes) {
            r.readOnlyTicks++;
            r.readOnlyNs += ns;
        }
        if (++total % EVERY == 0) report();
    }

    static boolean sameUnit(Entity other) {
        return other == cur || other.getRootVehicle() == cur;
    }

    /** the write barrier's hit for a part owned by {@code o} */
    public static void write(Owner o, int id) {
        if (cur == null || sameUnit(o.e)) return;
        f[CROSS] = true;
        CROSS_FIELDS.merge(cur.getType().toShortString() + " -> " + o.e.getType().toShortString() + " " + dev.lull.optsupport.sleep.WB.censusLabel(id), 1L, Long::sum);
    }

    public static void setBlock(Level level) {
        if (cur == null || level.isClientSide) return;
        f[BLOCK] = true;
        blockWrites++;
    }

    public static void queried(Level level, Iterable<?> found) {
        if (cur == null || level.isClientSide) return;
        f[QUERY] = true;
        queries++;
        if (found != null) for (Object o : found) if (o instanceof Entity en && !sameUnit(en)) {
            f[SEEN] = true;
            return;
        }
    }

    public static void effect() {
        if (cur != null) f[EFFECT] = true;
    }

    public static void spawn() {
        if (cur != null) f[SPAWN] = true;
    }

    private static final Map<String, Long> DRAWERS = new HashMap<>();
    private static final Map<Integer, Integer> LAST_DRAWS = new HashMap<>();
    private static final Map<Integer, Long> DRAW_COUNTS = new java.util.TreeMap<>();
    private static int tickDraws, drawUnitsThisTick;
    private static long lastGameTime = Long.MIN_VALUE, drawUnits, drawHits, drawAfterMiss, serverTicks, serverTicksAllHit;
    private static boolean missThisTick;
    private static final StackWalker SW = StackWalker.getInstance();

    static void drew() {
        if (cur != null && Thread.currentThread() == thread) {
            f[RAND] = true;
            tickDraws++;
            if ((randDraws++ & 7) == 0) {
                String who = SW.walk(st -> st.dropWhile(fr -> fr.getClassName().startsWith("dev.lull.TickCensus") || fr.getClassName().startsWith("net.minecraft.util."))
                        .limit(2).map(fr -> fr.getClassName().substring(fr.getClassName().lastIndexOf('.') + 1) + "." + fr.getMethodName() + ":" + fr.getLineNumber())
                        .reduce((a, b) -> a + " < " + b).orElse("?"));
                DRAWERS.merge(cur.getType().toShortString() + " " + who, 1L, Long::sum);
            }
        }
    }

    // ------------------------------------------------------------------ parts (re-watched at each own tick: paths and goals change)

    private static Field bodyRot;

    static void watchParts(Entity e) {
        Owner o = new Owner(e);
        w(e, o);
        if (e instanceof LivingEntity le) {
            w(le.getAttributes(), o);
            w(le.getCombatTracker(), o);
            w(le.getBrain(), o);
        }
        if (e instanceof Mob m) {
            w(m.goalSelector, o);
            w(m.targetSelector, o);
            for (WrappedGoal g : m.goalSelector.getAvailableGoals()) { w(g, o); w(g.getGoal(), o); }
            for (WrappedGoal g : m.targetSelector.getAvailableGoals()) { w(g, o); w(g.getGoal(), o); }
            w(m.getMoveControl(), o);
            w(m.getLookControl(), o);
            w(m.getJumpControl(), o);
            w(m.getNavigation(), o);
            w(m.getNavigation().getPath(), o);
            try {
                if (bodyRot == null) {
                    bodyRot = Mob.class.getDeclaredField("bodyRotationControl");
                    bodyRot.setAccessible(true);
                }
                w(bodyRot.get(m), o);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        if (e instanceof ItemEntity ie) w(ie.getItem(), o);
    }

    private static void w(Object part, Owner o) {
        if (part != null) dev.lull.optsupport.sleep.WB.censusWatch(part, o);
    }

    // ------------------------------------------------------------------ the level's random, counted

    private static Level wrapped;
    private static Thread thread;

    static void wrapRandom(Level level) {
        try {
            Field fr = Level.class.getDeclaredField("random");
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
            long off = u.objectFieldOffset(fr);
            Object r = u.getObject(level, off);
            if (!(r instanceof Counting)) u.putObject(level, off, new Counting((RandomSource) r));
            wrapped = level;
            thread = Thread.currentThread();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** the level's own RandomSource, every call passed through unchanged (the same sequence), draws counted */
    static final class Counting implements RandomSource {
        final RandomSource d;

        Counting(RandomSource d) {
            this.d = d;
        }

        @Override public RandomSource fork() { drew(); return d.fork(); }
        @Override public PositionalRandomFactory forkPositional() { drew(); return d.forkPositional(); }
        @Override public void setSeed(long seed) { d.setSeed(seed); }
        @Override public int nextInt() { drew(); return d.nextInt(); }
        @Override public int nextInt(int bound) { drew(); return d.nextInt(bound); }
        @Override public int nextIntBetweenInclusive(int min, int max) { drew(); return d.nextIntBetweenInclusive(min, max); }
        @Override public long nextLong() { drew(); return d.nextLong(); }
        @Override public boolean nextBoolean() { drew(); return d.nextBoolean(); }
        @Override public float nextFloat() { drew(); return d.nextFloat(); }
        @Override public double nextDouble() { drew(); return d.nextDouble(); }
        @Override public double nextGaussian() { drew(); return d.nextGaussian(); }
        @Override public double triangle(double c, double dev) { drew(); return d.triangle(c, dev); }
        @Override public void consumeCount(int count) { drew(); d.consumeCount(count); }
        @Override public int nextInt(int origin, int bound) { drew(); return d.nextInt(origin, bound); }
    }

    // ------------------------------------------------------------------ the report

    static void report() {
        List<Map.Entry<String, Row>> rows = new ArrayList<>(ROWS.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().ns, a.getValue().ns));
        Row all = new Row();
        for (Row r : ROWS.values()) {
            all.ticks += r.ticks; all.ns += r.ns; all.selfTicks += r.selfTicks; all.selfNs += r.selfNs;
            all.readOnlyTicks += r.readOnlyTicks; all.readOnlyNs += r.readOnlyNs;
            for (int k = 0; k < KINDS; k++) { all.kind[k] += r.kind[k]; all.kindNs[k] += r.kindNs[k]; }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "[sleep/census] %d root ticks; level random draws %d, block writes %d, queries %d%n", total, randDraws, blockWrites, queries));
        sb.append(String.format(Locale.ROOT, "  %-28s %9s %7s %6s %6s %6s", "type", "ticks", "ms", "self%", "selfT%", "ro T%"));
        for (String n : NAMES) sb.append(String.format(Locale.ROOT, " %13s", n));
        sb.append('\n');
        line(sb, "ALL", all);
        for (int i = 0; i < Math.min(25, rows.size()); i++) line(sb, rows.get(i).getKey(), rows.get(i).getValue());
        sb.append("  (per kind: % of ticks / % of tick time; self% = ticks touching nothing beyond the unit, selfT% their time share; ro = read others only)\n");
        List<Map.Entry<String, Long>> cf = new ArrayList<>(CROSS_FIELDS.entrySet());
        cf.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        sb.append("  cross writes (top):");
        for (int i = 0; i < Math.min(15, cf.size()); i++) sb.append("\n    ").append(cf.get(i).getValue()).append("  ").append(cf.get(i).getKey());
        List<Map.Entry<String, Long>> dr = new ArrayList<>(DRAWERS.entrySet());
        dr.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        sb.append("\n  level random drawn by (1 in 8 draws):");
        for (int i = 0; i < Math.min(15, dr.size()); i++) sb.append("\n    ").append(dr.get(i).getValue()).append("  ").append(dr.get(i).getKey());
        sb.append(String.format(Locale.ROOT, "%n  shared-random prediction by last tick: %d drawing unit-ticks, count as last tick %.1f %%, after an earlier miss in the same tick %.1f %%; server ticks with every count right %d of %d; draws per unit-tick %s",
                drawUnits, drawUnits == 0 ? 0 : 100.0 * drawHits / drawUnits, drawUnits == 0 ? 0 : 100.0 * drawAfterMiss / drawUnits, serverTicksAllHit, serverTicks, DRAW_COUNTS));
        System.out.println(sb);
    }

    private static void line(StringBuilder sb, String name, Row r) {
        if (r.ticks == 0) return;
        sb.append(String.format(Locale.ROOT, "  %-28s %9d %7.1f %6.1f %6.1f %6.1f", name, r.ticks, r.ns / 1e6,
                100.0 * r.selfTicks / r.ticks, r.ns == 0 ? 0 : 100.0 * r.selfNs / r.ns, r.ns == 0 ? 0 : 100.0 * r.readOnlyNs / r.ns));
        for (int k = 0; k < KINDS; k++)
            sb.append(String.format(Locale.ROOT, " %6.1f/%6.1f", 100.0 * r.kind[k] / r.ticks, r.ns == 0 ? 0 : 100.0 * r.kindNs[k] / r.ns));
        sb.append('\n');
    }
}
