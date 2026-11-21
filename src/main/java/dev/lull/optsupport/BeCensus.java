package dev.lull.optsupport;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * -Dlab.beCensus (11-18, measuring): what block-entity ticks do, tick by tick, on the server's vanilla walk - before any
 * parallel block-entity ticking is built (the user: mods' multiblocks, 200-1000 ticking block entities a chunk).
 * Each ticker's tick is marked with the effects reached while it runs on the server thread:
 * WRITE (a block set), OTHER_BE (another position's block entity looked up - a direct object write is possible),
 * RANDOM (the level's shared random drawn), ENTITY (an entity made), FX (sound, particles, level event, game event),
 * SCHED (a block / fluid tick scheduled - a global sub-tick counter), CHANGED (setChanged: comparator updates around),
 * NEIGHBORS (neighbour updates sent). A tick with none is quiet: only its own fields change - the kind a parallel
 * phase can run on a snapshot and commit in order without replaying anything.
 */
public final class BeCensus {
    private BeCensus() {}

    public static final boolean ON = Boolean.getBoolean("lab.beCensus");
    public static final int WRITE = 1, OTHER_BE = 2, RANDOM = 4, ENTITY = 8, FX = 16, SCHED = 32, CHANGED = 64, NEIGHBORS = 128;
    static final String[] NAMES = {"write", "otherBE", "random", "entity", "fx", "sched", "changed", "neighbors"};

    private static Thread server;
    private static Level level;
    private static BlockPos own;
    private static int flags;
    private static boolean in;

    private static final class Stat {
        long ticks, quiet, nanos, quietNanos;
        final long[] by = new long[8];
    }

    private static final Map<String, Stat> BY = new HashMap<>();
    private static long t0;
    private static String type;

    public static void begin(Level lv, BlockPos pos, String t) {
        if (lv.isClientSide) return;
        server = Thread.currentThread();
        level = lv;
        own = pos;
        type = t;
        flags = 0;
        in = true;
        t0 = System.nanoTime();
    }

    public static void end() {
        if (!in || Thread.currentThread() != server) return;
        long dt = System.nanoTime() - t0;
        in = false;
        Stat s = BY.computeIfAbsent(type, k -> new Stat());
        s.ticks++;
        s.nanos += dt;
        if (flags == 0) {
            s.quiet++;
            s.quietNanos += dt;
        }
        for (int i = 0; i < 8; i++) if ((flags & (1 << i)) != 0) s.by[i]++;
    }

    private static boolean active() {
        return in && Thread.currentThread() == server;
    }

    public static void mark(int f) {
        if (active()) flags |= f;
    }

    public static void blockEntityAt(Level lv, BlockPos pos) {
        if (active() && lv == level && !pos.equals(own)) flags |= OTHER_BE;
    }

    public static void random(Object rnd) {
        if (active() && rnd == level.random) flags |= RANDOM;
    }

    public static String report() {
        long ticks = 0, quiet = 0, nanos = 0, qn = 0;
        long[] by = new long[8];
        for (Stat s : BY.values()) {
            ticks += s.ticks;
            quiet += s.quiet;
            nanos += s.nanos;
            qn += s.quietNanos;
            for (int i = 0; i < 8; i++) by[i] += s.by[i];
        }
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT,
                "be census: %d ticks, %.0f ms; quiet %.1f %% of ticks, %.1f %% of time;", ticks, nanos / 1e6, 100.0 * quiet / Math.max(1, ticks), 100.0 * qn / Math.max(1, nanos)));
        for (int i = 0; i < 8; i++) b.append(String.format(java.util.Locale.ROOT, " %s %.1f %%", NAMES[i], 100.0 * by[i] / Math.max(1, ticks)));
        b.append(" | by type (time desc):");
        BY.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().nanos, x.getValue().nanos)).limit(40).forEach(e -> {
            Stat s = e.getValue();
            b.append(String.format(java.util.Locale.ROOT, " %s %d us/tick-of-all %.2f quiet %.0f%%", e.getKey(), s.ticks, s.nanos / 1e3 / Math.max(1, s.ticks), 100.0 * s.quiet / Math.max(1, s.ticks)));
            for (int i = 0; i < 8; i++) if (s.by[i] * 20 > s.ticks) b.append(' ').append(NAMES[i]).append(String.format(java.util.Locale.ROOT, "%.0f", 100.0 * s.by[i] / s.ticks));
            b.append(';');
        });
        return b.toString();
    }
}
