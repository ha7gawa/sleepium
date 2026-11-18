package dev.lull.optsupport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * -Dlab.occlusionFast (11-18, exact): vanilla's occlusion graph walk (SectionOcclusionGraph.runUpdates) with two answers
 * kept for the walk instead of worked out again. The graph is walked anew every 8 blocks the camera moves (100 000
 * sections at distance 32, 22 % of the meshing workers in flight):
 *
 * <ul>
 * <li>the ray test toward the camera, made for every edge into a far section (up to 6 times a section, ~18 steps each,
 * 45 % of the walk): its start corner depends on the section and on the axis of the edge only (the camera's section
 * centre is never on a section border), and it only checks that the sections on the way have nodes - nodes are only
 * ever added during a walk, so a ray that passed passes again. A pass is kept per section and axis for the walk;</li>
 * <li>hasAllNeighbors' four chunk lookups (14 % of the walk): the same for the 24 sections of a column - kept per
 * column for the walk (its distance check stays per section, as vanilla's).</li>
 * </ul>
 *
 * -Dlab.occlusionFast.verify=true: every full walk is made a second time the vanilla way on a twin storage and the two
 * compared (the set's order, every node's directions and step, the chunks waiting for neighbours).
 */
public final class OcclusionFast {
    private OcclusionFast() {}

    public static final boolean ON = Boolean.getBoolean("lab.occlusionFast");
    public static final boolean VERIFY = Boolean.getBoolean("lab.occlusionFast.verify");
    public static final int VERIFY_EVERY = Integer.getInteger("lab.occlusionFast.verifyEvery", 0);
    public static final boolean VERIFY_SELF = Boolean.getBoolean("lab.occlusionFast.verifySelf");

    /** per-thread walk memos, stamped with the walk's generation (full walks on a worker, partial ones on the render thread) */
    public static final class Memo {
        public int gen;
        public int[] ray = new int[0];          // section index * 3 + axis -> gen when the ray passed
        public long[] colKey = new long[0];     // grid column -> its chunk position at the check
        public int[] colGen = new int[0];       // grid column -> the view epoch when checked (0: never)
        public int[] colStamp = new int[0];     // grid column -> its neighbours' newest stamp then
        public boolean[] colOk = new boolean[0];

        public void begin(int sections, int columns) {
            if (++gen == 0) {   // wrapped: forget everything
                java.util.Arrays.fill(ray, 0);
                gen = 1;
            }
            if (ray.length < sections * 3) ray = new int[sections * 3];
            if (colGen.length < columns) {
                colKey = new long[columns];
                colGen = new int[columns];
                colStamp = new int[columns];
                colOk = new boolean[columns];
            }
        }
    }

    /** which chunks are in range changed (OcclusionChunkEpochMixin): every column answer is stale */
    private static volatile int viewEpoch = 1;
    /** a chunk arrived or was dropped: its slot (x, z mod 128) stamped with a new number */
    private static final java.util.concurrent.atomic.AtomicInteger STAMPS = new java.util.concurrent.atomic.AtomicInteger(1);
    private static final int[] STAMP = new int[128 * 128];

    public static void viewChanged() {
        viewEpoch++;
    }

    public static void chunkChanged(int x, int z) {
        STAMP[(x & 127) * 128 + (z & 127)] = STAMPS.incrementAndGet();
    }

    public static int viewEpoch() {
        return viewEpoch;
    }

    /** the newest stamp of a column's four neighbour chunks */
    public static int neighbourStamp(int cx, int cz) {
        return Math.max(Math.max(STAMP[((cx - 1) & 127) * 128 + (cz & 127)], STAMP[((cx + 1) & 127) * 128 + (cz & 127)]),
                Math.max(STAMP[(cx & 127) * 128 + ((cz - 1) & 127)], STAMP[(cx & 127) * 128 + ((cz + 1) & 127)]));
    }

    public static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    public static final AtomicLong WALKS = new AtomicLong(), FULL = new AtomicLong(), NANOS = new AtomicLong(), FULL_NANOS = new AtomicLong(),
            RAYS = new AtomicLong(), RAYS_KEPT = new AtomicLong(), COLS = new AtomicLong(), COLS_KEPT = new AtomicLong(),
            VERIFIED = new AtomicLong(), MISMATCHES = new AtomicLong(), VANILLA_NANOS = new AtomicLong(),
            SYNC = new AtomicLong(), SYNC_OURS = new AtomicLong(), SYNC_VANILLA = new AtomicLong(), SYNC_SECTIONS = new AtomicLong(), SYNC_DIFF = new AtomicLong();

    /** the first differences found (verify) */
    public static final java.util.List<String> DIFFS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static String report() {
        long f = Math.max(1, FULL.get());
        return String.format(java.util.Locale.ROOT, "occlusion walk: %d walks (%d full, %.2f ms each), rays %d made / %d kept, column checks %d made / %d kept%s",
                WALKS.get(), FULL.get(), FULL_NANOS.get() / 1e6 / f, RAYS.get(), RAYS_KEPT.get(), COLS.get(), COLS_KEPT.get(),
                VERIFY ? String.format(java.util.Locale.ROOT, ", verified %d full walks: %d differing (vanilla's walk %.2f ms each)", VERIFIED.get(), MISMATCHES.get(),
                        VANILLA_NANOS.get() / 1e6 / Math.max(1, VERIFIED.get())) + (SYNC.get() == 0 ? "" : String.format(java.util.Locale.ROOT, "; on the render thread %d graphs of %d sections: %d differing, %.2f ms ours / %.2f ms vanilla's",
                SYNC.get(), SYNC_SECTIONS.get() / SYNC.get(), SYNC_DIFF.get(), SYNC_OURS.get() / 1e6 / SYNC.get(), SYNC_VANILLA.get() / 1e6 / SYNC.get()))
                + (DIFFS.isEmpty() ? "" : " - first: " + DIFFS) : "");
    }
}
