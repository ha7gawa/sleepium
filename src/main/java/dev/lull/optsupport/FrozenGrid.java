package dev.lull.optsupport;

import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * lab.fast (Maximum speed, not exact): a dense entity section's spatial grid for the workers of a run. The section's
 * own index (SectionIndex) follows box changes on its owner thread only, so runs scan the whole section for every
 * query - O(n^2) in a pen (each mob's push query visits every mob of the section). During a run a section's entity
 * list does not change (moves between sections and spawns wait for the run's end), so a grid of its entities by their
 * centres, built once per run on first use, stays valid; boxes move by less than a block a tick, so a query takes
 * the cells of its box grown by the largest half size in the section plus one block and tests the current boxes.
 * 8 x 8 x 8 cells of 2 blocks, CSR arrays.
 */
public final class FrozenGrid {
    /** a section's grid (EntitySectionIndexMixin) */
    public interface Holder {
        Object sleep$frozen();
        void sleep$setFrozen(Object g);
    }

    public static final int MIN = 48;
    private static final int N = 8, CELL = 2;
    public static long builds, queries, visited;

    private final long epoch;
    private final int ox, oy, oz;
    private final int[] start;
    private final int[] items;
    private final double reach;

    private FrozenGrid(long epoch, long key, List<? extends EntityAccess> list) {
        this.epoch = epoch;
        ox = SectionPos.sectionToBlockCoord(SectionPos.x(key));
        oy = SectionPos.sectionToBlockCoord(SectionPos.y(key));
        oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
        int n = list.size();
        int[] cellOf = new int[n];
        int[] count = new int[N * N * N + 1];
        double r = 0;
        for (int i = 0; i < n; i++) {
            AABB bb = list.get(i).getBoundingBox();
            int c = cell((bb.minX + bb.maxX) * 0.5 - ox, (bb.minY + bb.maxY) * 0.5 - oy, (bb.minZ + bb.maxZ) * 0.5 - oz);
            cellOf[i] = c;
            count[c + 1]++;
            r = Math.max(r, Math.max(bb.getXsize(), Math.max(bb.getYsize(), bb.getZsize())) * 0.5);
        }
        for (int c = 0; c < N * N * N; c++) count[c + 1] += count[c];
        start = count.clone();
        items = new int[n];
        int[] fill = count;   // (next free place per cell)
        for (int i = 0; i < n; i++) items[fill[cellOf[i]]++] = i;
        reach = r + 1.0;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v >= N ? N - 1 : v;
    }

    private static int cell(double x, double y, double z) {
        return (clamp((int) Math.floor(y / CELL)) * N + clamp((int) Math.floor(z / CELL))) * N + clamp((int) Math.floor(x / CELL));
    }

    /** a worker's query of a dense section during a lab.fast run; null: not served (key unknown) */
    public static <T extends EntityAccess> AbortableIterationConsumer.Continuation query(Object section, long key, List<T> list, AABB b,
                                                                                         AbortableIterationConsumer<T> consumer) {
        if (key == Long.MIN_VALUE) return null;
        long ep = SpecTicks.runEpoch();
        Holder h = (Holder) section;
        FrozenGrid g = h.sleep$frozen() instanceof FrozenGrid f && f.epoch == ep ? f : null;
        if (g == null) {
            synchronized (section) {
                g = h.sleep$frozen() instanceof FrozenGrid f && f.epoch == ep ? f : null;
                if (g == null) {
                    g = new FrozenGrid(ep, key, list);
                    h.sleep$setFrozen(g);
                    builds++;
                }
            }
        }
        queries++;
        double m = g.reach;
        int x0 = clamp((int) Math.floor((b.minX - m - g.ox) / CELL)), x1 = clamp((int) Math.floor((b.maxX + m - g.ox) / CELL));
        int y0 = clamp((int) Math.floor((b.minY - m - g.oy) / CELL)), y1 = clamp((int) Math.floor((b.maxY + m - g.oy) / CELL));
        int z0 = clamp((int) Math.floor((b.minZ - m - g.oz) / CELL)), z1 = clamp((int) Math.floor((b.maxZ + m - g.oz) / CELL));
        int seen = 0;
        try {
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    for (int x = x0; x <= x1; x++) {
                        int c = (y * N + z) * N + x;
                        for (int j = g.start[c], e = g.start[c + 1]; j < e; j++) {
                            T t = list.get(g.items[j]);
                            seen++;
                            if (t.getBoundingBox().intersects(b) && consumer.accept(t).shouldAbort()) return AbortableIterationConsumer.Continuation.ABORT;
                        }
                    }
        } finally {
            visited += seen;
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }
}
