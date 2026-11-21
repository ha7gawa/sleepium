package dev.lull.optsupport;

import dev.lull.opt.ChunkHeightmapsAccessor;
import dev.lull.opt.HeightmapDataAccessor;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * OreScan (-Dlab.opt.WorldgenOreScanMixin): the two scans of OreFeature with less work per step, the same answers.
 *
 * <p><b>The column test of place.</b> Vanilla walks the ore's box column by column (x outer, z inner) and calls
 * {@code level.getHeight(OCEAN_FLOOR_WG, x, z)} until a column reaches the ore's lowest y; doPlace's arguments do not
 * depend on which column that was, so the walk only answers "is there such a column". Ores placed above the terrain
 * (the upper coal / iron / emerald bands: 30 + 90 + ... placements a chunk, 81-121 columns each) fail every column:
 * ~10 k lookups a chunk, each a region chunk lookup, an EnumMap get, a heightmap read and the dimension type's minY.
 * Here the walk keeps vanilla's column order and fetches each chunk once, with the same {@code level.getChunk(cx, cz)}
 * call WorldGenRegion.getHeight makes, the first time one of its columns is reached (so an unavailable chunk throws at
 * the same column as in vanilla). For a plain ProtoChunk with the heightmap already there, a column's
 * {@code getHeight(...) + 1} is {@code data.get(x + z * 16) + chunk.getMinBuildHeight() - 1 + 1} (ChunkAccess.getHeight
 * and Heightmap.getFirstAvailable, neither overridden by ProtoChunk) - read from the same storage; otherwise (other
 * chunk classes, an unprimed heightmap: vanilla primes it in getHeight) the column goes through chunk.getHeight as in
 * vanilla. Other levels than WorldGenRegion take vanilla's loop.
 *
 * <p><b>The block scan of doPlace</b> (see WorldgenFeatureOreMixin): blobs are spaced a quarter block apart with
 * radii around one block, so most cells inside a blob's sphere were already visited by an earlier blob and are skipped
 * by the bit set. Vanilla tests the sphere (three divisions' worth of doubles) and the build height before looking at
 * the bit; both tests are pure, so the bit is looked at first (an index computed from ints only, the same expression,
 * never negative: every term is a non-negative difference times small positive sizes) and the sphere test only runs for
 * cells not seen yet, computed with vanilla's expression and evaluation order ((d5*d5 + d6*d6) + d7*d7). The build
 * height test depends on y only and is asked once per row. Visited cells, their order and the body (writes, random
 * draws) are vanilla's.
 *
 * <p>-Dlab.featcpu.verify=true (measurement only): place's answer is compared with vanilla's column loop and every
 * doPlace's visit sequence with vanilla's enumeration (computed purely beforehand with its own bit set).
 */
public final class OreScan {
    public static final boolean ON = Boolean.getBoolean("lab.opt.all") || Boolean.getBoolean("lab.opt.WorldgenOreScanMixin");
    public static final boolean VERIFY = Boolean.getBoolean("lab.featcpu.verify");
    private static final AtomicLong COL_CHECKS = new AtomicLong(), COL_BAD = new AtomicLong(), SCAN_CHECKS = new AtomicLong(),
            SCAN_BAD = new AtomicLong(), SCAN_CELLS = new AtomicLong();

    private static final ThreadLocal<OreScan> TL = ThreadLocal.withInitial(OreScan::new);

    static {
        if (VERIFY) Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[sleep/featcpu] final: " + summary() + "; "
                + PaletteCopy.summary() + "; " + RegionBlockVerify.summary()), "featcpu-verify-summary"));
    }
    private final ChunkAccess[] chunks = new ChunkAccess[16];
    private final BitStorage[] data = new BitStorage[16];
    private final int[] minY = new int[16];
    private final boolean[] loaded = new boolean[16];
    /** verify: vanilla's visits (x, y, z triples) and the new scan's */
    private int[] want = new int[3 * 256], got = new int[3 * 256];
    private int nWant, nGot;
    private final BitSet vbits = new BitSet(512);
    /**
     * Reject filter: per chunk of the current region the highest column of its OCEAN_FLOOR_WG heightmap (as getHeight +
     * 1 gives it). A box all of whose chunks are lower than the ore's lowest y has no column that passes, which is
     * vanilla's answer after its full walk. Valid for the region's lifetime: every chunk of a FEATURES region has
     * persisted status >= CARVERS (the step's requirement), whose heightmapsAfter are the final heightmaps - vanilla's
     * ProtoChunk.setBlockState updates only those, primeHeightmaps only adds missing ones, setHeightmap is a load-time
     * call - so the WORLDGEN heightmaps no longer change. Only filled for plain ProtoChunks at >= CARVERS with the
     * heightmap present, and only for chunks the region has (hasChunk); anything else never rejects.
     */
    private java.lang.ref.WeakReference<WorldGenLevel> region;
    private final long[] maxKey = new long[32];
    private final int[] maxVal = new int[32];
    private int maxN;
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private OreScan() {}

    /** OreFeature.place's column loop: is there a column of the box with l <= getHeight(OCEAN_FLOOR_WG, x, z) */
    public static boolean anyColumn(WorldGenLevel level, int l, int k, int i1, int j1) {
        boolean r;
        if (level.getClass() != WorldGenRegion.class) r = vanillaColumns(level, l, k, i1, j1);
        else {
            int cx0 = k >> 4, cz0 = i1 >> 4, nx = ((k + j1) >> 4) - cx0 + 1, nz = ((i1 + j1) >> 4) - cz0 + 1;
            if (j1 < 0 || nx > 4 || nz > 4) r = vanillaColumns(level, l, k, i1, j1);
            else r = TL.get().columns(level, l, k, i1, j1, cx0, cz0, nz, nx * nz);
        }
        if (VERIFY) {
            boolean v = vanillaColumns(level, l, k, i1, j1);
            long c = COL_CHECKS.incrementAndGet();
            if (v != r) {
                long b = COL_BAD.incrementAndGet();
                if (b <= 20) System.out.println("[sleep/featcpu] ORE COLUMN MISMATCH at " + k + " " + l + " " + i1 + " size " + j1 + ": vanilla " + v + " ours " + r);
            }
            if ((c & ((1 << 18) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
        }
        return r;
    }

    private static boolean vanillaColumns(WorldGenLevel level, int l, int k, int i1, int j1) {
        for (int l1 = k; l1 <= k + j1; l1++)
            for (int i2 = i1; i2 <= i1 + j1; i2++)
                if (l <= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, l1, i2)) return true;
        return false;
    }

    private boolean columns(WorldGenLevel level, int l, int k, int i1, int j1, int cx0, int cz0, int nz, int slots) {
        for (int s = 0; s < slots; s++) {
            loaded[s] = false;
            chunks[s] = null;
            data[s] = null;
        }
        if (reject(level, l, cx0, cz0, nz, slots)) return false;
        try {
            for (int l1 = k; l1 <= k + j1; l1++) {
                int sx = ((l1 >> 4) - cx0) * nz;
                for (int i2 = i1; i2 <= i1 + j1; i2++) {
                    int s = sx + ((i2 >> 4) - cz0);
                    if (!loaded[s]) load(level, s, l1, i2);
                    BitStorage d = data[s];
                    int h = d != null ? d.get((l1 & 15) + (i2 & 15) * 16) + minY[s]
                            : chunks[s].getHeight(Heightmap.Types.OCEAN_FLOOR_WG, l1 & 15, i2 & 15) + 1;
                    if (l <= h) return true;
                }
            }
            return false;
        } finally {
            for (int s = 0; s < slots; s++) {
                chunks[s] = null;
                data[s] = null;
            }
        }
    }

    private boolean reject(WorldGenLevel level, int l, int cx0, int cz0, int nz, int slots) {
        if (region == null || region.get() != level) {
            region = new java.lang.ref.WeakReference<>(level);
            maxN = 0;
        }
        for (int s = 0; s < slots; s++) {
            int m = chunkMax(level, cx0 + s / nz, cz0 + s % nz);
            if (m == UNKNOWN || l <= m) return false;
        }
        return true;
    }

    private int chunkMax(WorldGenLevel level, int cx, int cz) {
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        for (int i = 0; i < maxN; i++)
            if (maxKey[i] == key) return maxVal[i];
        if (!level.hasChunk(cx, cz)) return UNKNOWN;
        int m = UNKNOWN;
        ChunkAccess c = level.getChunk(cx, cz);
        if (c.getClass() == ProtoChunk.class && c.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS)) {
            Heightmap hm = ((ChunkHeightmapsAccessor) c).sleep$heightmaps().get(Heightmap.Types.OCEAN_FLOOR_WG);
            if (hm != null && hm.getClass() == Heightmap.class) {
                HeightmapDataAccessor a = (HeightmapDataAccessor) hm;
                BitStorage d = a.sleep$data();
                int top = Integer.MIN_VALUE;
                for (int i = 0; i < 256; i++) top = Math.max(top, d.get(i));
                m = top + a.sleep$chunk().getMinBuildHeight();
            }
        }
        if (maxN < maxKey.length) {
            maxKey[maxN] = key;
            maxVal[maxN++] = m;
        }
        return m;
    }

    private void load(WorldGenLevel level, int s, int x, int z) {
        ChunkAccess c = level.getChunk(x >> 4, z >> 4);   // WorldGenRegion.getHeight's own lookup
        chunks[s] = c;
        data[s] = null;
        if (c.getClass() == ProtoChunk.class) {
            Heightmap hm = ((ChunkHeightmapsAccessor) c).sleep$heightmaps().get(Heightmap.Types.OCEAN_FLOOR_WG);
            if (hm != null && hm.getClass() == Heightmap.class) {
                HeightmapDataAccessor a = (HeightmapDataAccessor) hm;
                data[s] = a.sleep$data();
                minY[s] = a.sleep$chunk().getMinBuildHeight();
            }
        }
        loaded[s] = true;
    }

    // ---- doPlace's target tests ----

    /**
     * OreFeature.canPlaceOre starts with {@code targetState.target.test(state, random)}. For TagMatchTest (state.is(tag)),
     * BlockMatchTest (state.is(block)), BlockStateMatchTest (state == blockState) and AlwaysTrueTest the answer depends
     * on the state only (no random draw, the tags bound to the state's holder do not change during a call); a blob of
     * ore cells is mostly one state (stone / deepslate), and a tag test is a hash set probe with the TagKey record's
     * hashCode / equals. Per call and target the last state and answer are kept; other tests are asked every time.
     */
    private final BlockState[] memoState = new BlockState[8];
    private final boolean[] memoPass = new boolean[8], pure = new boolean[8];
    private static final AtomicLong TEST_CHECKS = new AtomicLong(), TEST_BAD = new AtomicLong();

    public static OreScan local() {
        return TL.get();
    }

    public void startTests(java.util.List<OreConfiguration.TargetBlockState> targets, int nt) {
        for (int t = 0; t < nt && t < 8; t++) {
            Class<?> c = targets.get(t).target.getClass();
            pure[t] = c == TagMatchTest.class || c == BlockMatchTest.class || c == BlockStateMatchTest.class || c == AlwaysTrueTest.class;
            memoState[t] = null;
        }
    }

    public boolean test(int t, OreConfiguration.TargetBlockState target, BlockState state, RandomSource random) {
        if (t >= 8 || !pure[t]) return target.target.test(state, random);
        boolean pass;
        if (memoState[t] == state) {
            pass = memoPass[t];
            if (VERIFY) {
                long c = TEST_CHECKS.incrementAndGet();
                if (pass != target.target.test(state, random) && TEST_BAD.incrementAndGet() <= 20)
                    System.out.println("[sleep/featcpu] ORE TEST MISMATCH " + target.target + " " + state);
                if ((c & ((1 << 22) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
            }
        } else {
            pass = target.target.test(state, random);
            memoState[t] = state;
            memoPass[t] = pass;
        }
        return pass;
    }

    /** OreFeature.shouldSkipAirCheck (protected there), statement for statement */
    public static boolean shouldSkipAirCheck(RandomSource random, float chance) {
        if (chance <= 0.0F) {
            return true;
        } else {
            return chance >= 1.0F ? false : random.nextFloat() >= chance;
        }
    }

    // ---- verify of doPlace's scan ----

    /** a scan state for one doPlace call (verify only) */
    public static OreScan verifyStart() {
        OreScan s = TL.get();
        s.nWant = 0;
        s.nGot = 0;
        return s;
    }

    /** vanilla's visit enumeration of doPlace (pure: its own bit set, level.isOutsideBuildHeight) */
    public void vanillaVisits(WorldGenLevel level, double[] adouble, int j, int x, int y, int z, int width, int height) {
        BitSet bitset = vbits;
        bitset.clear();
        for (int j4 = 0; j4 < j; j4++) {
            double d9 = adouble[j4 * 4 + 3];
            if (!(d9 < 0.0)) {
                double d11 = adouble[j4 * 4 + 0];
                double d13 = adouble[j4 * 4 + 1];
                double d15 = adouble[j4 * 4 + 2];
                int k4 = Math.max(Mth.floor(d11 - d9), x);
                int l = Math.max(Mth.floor(d13 - d9), y);
                int i1 = Math.max(Mth.floor(d15 - d9), z);
                int j1 = Math.max(Mth.floor(d11 + d9), k4);
                int k1 = Math.max(Mth.floor(d13 + d9), l);
                int l1 = Math.max(Mth.floor(d15 + d9), i1);
                for (int i2 = k4; i2 <= j1; i2++) {
                    double d5 = ((double) i2 + 0.5 - d11) / d9;
                    if (d5 * d5 < 1.0) {
                        for (int j2 = l; j2 <= k1; j2++) {
                            double d6 = ((double) j2 + 0.5 - d13) / d9;
                            if (d5 * d5 + d6 * d6 < 1.0) {
                                for (int k2 = i1; k2 <= l1; k2++) {
                                    double d7 = ((double) k2 + 0.5 - d15) / d9;
                                    if (d5 * d5 + d6 * d6 + d7 * d7 < 1.0 && !level.isOutsideBuildHeight(j2)) {
                                        int l2 = i2 - x + (j2 - y) * width + (k2 - z) * width * height;
                                        if (!bitset.get(l2)) {
                                            bitset.set(l2);
                                            if (nWant * 3 + 3 > want.length) want = java.util.Arrays.copyOf(want, want.length * 2);
                                            want[nWant * 3] = i2;
                                            want[nWant * 3 + 1] = j2;
                                            want[nWant * 3 + 2] = k2;
                                            nWant++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** the new scan visited this cell */
    public void visited(int i2, int j2, int k2) {
        if (nGot * 3 + 3 > got.length) got = java.util.Arrays.copyOf(got, got.length * 2);
        got[nGot * 3] = i2;
        got[nGot * 3 + 1] = j2;
        got[nGot * 3 + 2] = k2;
        nGot++;
    }

    public void verifyEnd(int x, int y, int z) {
        boolean ok = nGot == nWant && java.util.Arrays.equals(want, 0, nWant * 3, got, 0, nGot * 3);
        long c = SCAN_CHECKS.incrementAndGet();
        SCAN_CELLS.addAndGet(nWant);
        if (!ok) {
            long b = SCAN_BAD.incrementAndGet();
            if (b <= 20) System.out.println("[sleep/featcpu] ORE SCAN MISMATCH at " + x + " " + y + " " + z + ": vanilla " + nWant + " cells, ours " + nGot);
        }
        if ((c & ((1 << 16) - 1)) == 0) System.out.println("[sleep/featcpu] " + summary());
    }

    public static String summary() {
        return "ore tests " + TEST_CHECKS.get() + " remembered checked, " + TEST_BAD.get() + " mismatches; ore columns " + COL_CHECKS.get() + " checked, " + COL_BAD.get() + " mismatches; ore scans " + SCAN_CHECKS.get() + " checked ("
                + SCAN_CELLS.get() + " cells), " + SCAN_BAD.get() + " mismatches";
    }
}
