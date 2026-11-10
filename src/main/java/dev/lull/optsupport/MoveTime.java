package dev.lull.optsupport;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Measurement only (-Dsleep.moveTime=true, MoveTime*Mixin): direct timings of a mob's tick by part - tick, aiStep, the
 * AI (serverAiStep), travel, move, collide, checkInsideBlocks, the fluid scan, baseTick, pushEntities - as self time (a
 * part's timed children subtracted), per entity type. Printed every 600 game ticks as us per entity tick, then reset.
 * Server thread only; -Dsleep.moveTime.client=true: the client's entities on the render thread instead.
 */
public final class MoveTime {
    public static final boolean ON = Boolean.getBoolean("sleep.moveTime");
    private static final boolean CLIENT = Boolean.getBoolean("sleep.moveTime.client");
    public static final String[] PARTS = {"tick", "aiStep", "ai", "travel", "move", "collide", "insideBlocks", "fluids", "baseTick", "push", "support", "fireCheck", "speed", "fall"};
    public static final int TICK = 0, AISTEP = 1, AI = 2, TRAVEL = 3, MOVE = 4, COLLIDE = 5, INSIDE = 6, FLUIDS = 7, BASE = 8, PUSH = 9, SUPPORT = 10, FIRE = 11, SPEED = 12, FALL = 13;
    private static final Map<EntityType<?>, long[]> BY_TYPE = new IdentityHashMap<>();   // PARTS self ns, then entity ticks
    private static final long[] T0 = new long[64], CHILD = new long[64];
    private static final int[] PART = new int[64];
    private static final long[][] ROW = new long[64][];
    private static int depth;
    private static Thread thread;
    private static long lastPrint = -1;

    private MoveTime() {}

    public static void enter(int part, Entity e) {
        if (thread == null) {
            if (!Thread.currentThread().getName().equals(CLIENT ? "Render thread" : "Server thread")) return;
            thread = Thread.currentThread();
        }
        if (Thread.currentThread() != thread || e.level().isClientSide != CLIENT) return;
        if (depth >= 64) { depth++; return; }
        long[] row = BY_TYPE.computeIfAbsent(e.getType(), k -> new long[PARTS.length + 1]);
        if (part == TICK) row[PARTS.length]++;
        PART[depth] = part;
        ROW[depth] = row;
        CHILD[depth] = 0;
        T0[depth++] = System.nanoTime();
    }

    public static void exit(Entity e) {
        if (Thread.currentThread() != thread || e.level().isClientSide != CLIENT) return;
        if (--depth >= 64 || depth < 0) { if (depth < 0) depth = 0; return; }
        long dt = System.nanoTime() - T0[depth];
        ROW[depth][PART[depth]] += dt - CHILD[depth];
        if (depth > 0) CHILD[depth - 1] += dt;
        if (PART[depth] == TICK && depth == 0) {
            long gt = e.level().getGameTime();
            if (gt % 600 == 0 && gt != lastPrint) {
                lastPrint = gt;
                print();
            }
        }
    }

    private static void print() {
        StringBuilder sb = new StringBuilder("[sleep/movetime] us per entity tick, self time:");
        List<Map.Entry<EntityType<?>, long[]>> l = new ArrayList<>(BY_TYPE.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        sb.append(String.format(Locale.ROOT, "%n   %-14s %8s", "type", "ticks"));
        for (String p : PARTS) sb.append(String.format(Locale.ROOT, " %12s", p));
        sb.append(String.format(Locale.ROOT, " %10s", "total"));
        for (var en : l) {
            long[] r = en.getValue();
            long n = Math.max(1, r[PARTS.length]);
            if (r[PARTS.length] == 0) continue;
            String name = EntityType.getKey(en.getKey()).getPath();
            sb.append(String.format(Locale.ROOT, "%n   %-14s %8d", name, r[PARTS.length]));
            long sum = 0;
            for (int i = 0; i < PARTS.length; i++) {
                sb.append(String.format(Locale.ROOT, " %12.2f", r[i] / 1e3 / n));
                sum += r[i];
            }
            sb.append(String.format(Locale.ROOT, " %10.2f", sum / 1e3 / n));
        }
        System.out.println(sb);
        BY_TYPE.clear();
    }
}
