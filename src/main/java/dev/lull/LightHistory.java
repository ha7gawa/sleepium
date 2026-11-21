package dev.lull;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Measurement only (-Dlab.light.history=true with -Dlab.light.probe, notes/84): a ring of the level's block-light events -
 * every stored level written (old -> new), every checkNode (emission, stored level, light on), block-light sections
 * created / removed, light enabled per column, checkBlock submitted, and the light engine reading a chunk it cannot
 * get (vanilla then reads BEDROCK). When fixcheck finds the level's block light below the from-scratch light, it walks
 * from the first differing cell toward its source along the from-scratch light (each step to the neighbour one brighter)
 * to the first cell whose level agrees, and prints the history of the pair where the light did not cross.
 */
public final class LightHistory {
    private LightHistory() {}

    public static final boolean ON = Boolean.getBoolean("lab.light.history") && LightProbe.ON;
    /** the private from-scratch engine of fixcheck writes too: not recorded */
    public static volatile boolean paused;
    public static volatile int batch;

    public static final int WRITE = 1, CHECK = 2, SEC_ADD = 3, SEC_RM = 4, ENABLE = 5, SUBMIT = 6, NULL_CHUNK = 7, SRC_SCAN = 8;
    private static final String[] NAMES = {"?", "write", "checkNode", "section+", "section-", "lightEnabled", "checkBlock submitted", "chunk not available (BEDROCK)", "sources of chunk"};
    private static final int N = 1 << 23;   // 8 M events, 16 + 8 bytes each
    private static final long[] POS = ON ? new long[N] : null, INFO = ON ? new long[N] : null;
    private static final int[] META = ON ? new int[N] : null;
    private static long count;
    public static long nullChunks;

    /** a = new / emission / flag, b = old / stored, c = light on etc. */
    public static synchronized void rec(int type, long pos, int a, int b, int c) {
        if (paused) return;
        int i = (int) (count++ & (N - 1));
        POS[i] = pos;
        INFO[i] = ((long) type) | ((long) (a & 0xFF) << 8) | ((long) (b & 0xFF) << 16) | ((long) (c & 0xFF) << 24) | ((long) (System.nanoTime() / 1000 & 0xFFFFFFFFL) << 32);
        META[i] = batch;
        if (type == NULL_CHUNK) nullChunks++;
    }

    private static String fmt(int i) {
        long info = INFO[i];
        int type = (int) (info & 0xFF), a = (int) (info >> 8 & 0xFF), b = (int) (info >> 16 & 0xFF), c = (int) (info >> 24 & 0xFF);
        long t = info >>> 32;
        String where = type == SEC_ADD || type == SEC_RM ? SectionPos.of(POS[i]).toString()
                : type == ENABLE || type == NULL_CHUNK || type == SRC_SCAN ? SectionPos.x(POS[i]) + "," + SectionPos.z(POS[i]) : BlockPos.of(POS[i]).toShortString();
        String what = switch (type) {
            case WRITE -> b + " -> " + a;
            case CHECK -> "emission " + a + " stored " + b + " lightOn " + (c & 1) + " sectionStored " + (c >> 1 & 1);
            case ENABLE -> a == 1 ? "on" : "off";
            default -> "";
        };
        return String.format(Locale.ROOT, "    #%d batch %d t %d us: %s %s %s", i, META[i], t, NAMES[Math.min(type, NAMES.length - 1)], where, what);
    }

    /** the events of one cell (and of its section / column), oldest first, at most max */
    static synchronized String history(BlockPos p, int max) {
        long cell = p.asLong(), sec = SectionPos.asLong(p), col = SectionPos.asLong(SectionPos.blockToSectionCoord(p.getX()), 0, SectionPos.blockToSectionCoord(p.getZ()));
        StringBuilder b = new StringBuilder();
        long from = Math.max(0, count - N);
        java.util.ArrayList<Integer> hits = new java.util.ArrayList<>();
        for (long k = from; k < count; k++) {
            int i = (int) (k & (N - 1));
            int type = (int) (INFO[i] & 0xFF);
            boolean mine = (type == WRITE || type == CHECK || type == SUBMIT) && POS[i] == cell
                    || (type == SEC_ADD || type == SEC_RM) && POS[i] == sec
                    || (type == ENABLE || type == NULL_CHUNK || type == SRC_SCAN) && SectionPos.x(POS[i]) == SectionPos.x(col) && SectionPos.z(POS[i]) == SectionPos.z(col);
            if (mine) hits.add(i);
        }
        int skip = Math.max(0, hits.size() - max);
        if (skip > 0) b.append("    (").append(skip).append(" older events)\n");
        for (int j = skip; j < hits.size(); j++) b.append(fmt(hits.get(j))).append('\n');
        return b.toString();
    }

    /**
     * From a cell where the level's block light is below the relight's: step to the neighbour with the relight's level
     * one higher (toward the source) until the level agrees; the pair (agreeing cell, first lower cell) is where the light
     * stopped. Prints states, levels and both histories.
     */
    static String explain(ServerLevel level, LevelLightEngine real, LevelLightEngine mine, BlockPos start) {
        StringBuilder b = new StringBuilder("\n  history (" + count + " events recorded, " + nullChunks + " unavailable-chunk reads) from " + start.toShortString() + ":\n");
        BlockPos cur = start;
        for (int steps = 0; steps < 16; steps++) {
            int rNew = mine.getLayerListener(LightLayer.BLOCK).getLightValue(cur);
            BlockPos next = null;
            for (Direction d : Direction.values()) {
                BlockPos n = cur.relative(d);
                if (mine.getLayerListener(LightLayer.BLOCK).getLightValue(n) == rNew + 1) {
                    next = n;
                    break;
                }
            }
            if (next == null) {
                b.append("  no brighter neighbour at ").append(cur.toShortString()).append(" (a source?)\n");
                break;
            }
            int realNext = real.getLayerListener(LightLayer.BLOCK).getLightValue(next), newNext = mine.getLayerListener(LightLayer.BLOCK).getLightValue(next);
            if (realNext == newNext) {
                b.append(String.format(Locale.ROOT, "  light stopped between %s (level %d, state %s) and %s (level %d, relight %d, state %s)%n",
                        next.toShortString(), realNext, level.getBlockState(next), cur.toShortString(), real.getLayerListener(LightLayer.BLOCK).getLightValue(cur),
                        rNew, level.getBlockState(cur)));
                b.append("  history of ").append(next.toShortString()).append(":\n").append(history(next, 40));
                b.append("  history of ").append(cur.toShortString()).append(":\n").append(history(cur, 40));
                break;
            }
            cur = next;
        }
        return b.toString();
    }
}
