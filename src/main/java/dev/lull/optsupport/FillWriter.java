package dev.lull.optsupport;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * FillWrite (-Dlab.opt.FillWrite, 11-21, lab): noise fill's block writes straight into the section's packed storage.
 *
 * <p>doFill writes every block of a chunk at most once (each (x, y, z) belongs to one cell and one step of it), top down
 * in every column, into sections that are fresh: a single-value palette of AIR over a zero-bit storage. For such a
 * section vanilla's writes do: the first non-AIR state resizes to a linear palette of 4 bits (onResize: a new Data, its
 * palette given AIR by copyFrom over 4096 zero entries, then the new state); every further state is appended to the
 * linear palette in first-write order until it holds 16; the 17th resizes to a hash palette. Each write sets the
 * position's 4 bits (it was 0 = AIR) and counts the state: non-empty, randomly ticking, non-empty fluid (the old state is
 * AIR, which counts nothing).
 *
 * <p>Here, per section: the same Data (PalettedContainer.createOrReuseData, as onResize makes it; AIR at id 0 by the
 * palette's own idFor instead of 4096 copies of it), the same palette entries appended by the palette's own idFor in
 * the same order, the 4 bits OR-ed into the raw long (16 a long, as SimpleBitStorage packs 4 bits), and the counts per
 * palette id, set on the section once at the end. A section that is not fresh, or would need a 17th state, goes back
 * to vanilla's own write for the rest of the fill (its counts set first, so vanilla continues from vanilla's state).
 *
 * <p>Heightmaps: doFill calls update for both worldgen heightmaps at every write. In a column written top down, once a
 * block opaque for a heightmap is written its height is that y + 1 and every later write is at least 2 lower - update
 * returns at its first test. Those calls are skipped (a bit per column); until then vanilla's update runs. update only
 * reads the chunk when a non-opaque block lands right under the current height (y == first available - 1); before such
 * a call the deferred counts are set (hasOnlyAir reads them).
 *
 * <p>The section modification counter (SectionModCountMixin, collision memo) is bumped once per written section.
 */
public final class FillWriter {
    public static final boolean ON = Boolean.getBoolean("lab.opt.FillWrite");
    /**
     * -Dlab.fill.verify: every write is also recorded; at the end of the fill the record is replayed through vanilla's
     * LevelChunkSection.setBlockState into fresh sections and through Heightmap.update into fresh heightmaps, and those
     * are compared with ours: palette class, entries in order, bits, packed longs, the three counts, heightmap data.
     */
    public static final boolean VERIFY = Boolean.getBoolean("lab.fill.verify");
    /** negative control for the verify: the first opaque write of a column skips the OCEAN_FLOOR_WG update */
    private static final boolean BREAK = Boolean.getBoolean("lab.fill.break");
    private static final AtomicLong V_CHUNKS = new AtomicLong(), V_SECTIONS = new AtomicLong(), V_BAD = new AtomicLong(),
            V_HEIGHTS = new AtomicLong(), V_HBAD = new AtomicLong(), V_VANILLA = new AtomicLong();

    private static final ThreadLocal<FillWriter> TL = ThreadLocal.withInitial(FillWriter::new);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int UNSEEN = 0, FAST = 1, VANILLA = 2;

    public static FillWriter get() {
        return TL.get();
    }

    // per section (index = chunk section index)
    private LevelChunkSection[] sec = new LevelChunkSection[32];
    private int[] mode = new int[32];
    private long[][] raw = new long[32][];
    @SuppressWarnings("unchecked")
    private Palette<BlockState>[] pal = new Palette[32];
    private BlockState[][] vals = new BlockState[32][];
    private int[] size = new int[32];
    private int[][] cnt = new int[32][];
    private int nSec;
    // last write's section and state
    private int lastSec = -1;
    private BlockState lastState;
    private int lastId;
    // heightmaps
    private Heightmap h0, h1;
    private Predicate<BlockState> op0, op1;
    private final long[] done0 = new long[4], done1 = new long[4];
    /** per column: the heightmap's first available y while the column is not done (update changes it only when it runs) */
    private final int[] cur0 = new int[256], cur1 = new int[256];
    // verify: the writes in order
    private ChunkAccess chunk;
    private boolean hFresh;
    private int vn;
    private int[] vPos = new int[1 << 16], vY = new int[1 << 16];
    private BlockState[] vState = new BlockState[1 << 16];
    private boolean[] opened = new boolean[32];

    private FillWriter() {}

    public void begin(ChunkAccess chunk, int sectionCount, Heightmap h0, Heightmap.Types t0, Heightmap h1, Heightmap.Types t1) {
        if (sectionCount > sec.length) {
            int n = Math.max(sectionCount, sec.length * 2);
            sec = Arrays.copyOf(sec, n);
            mode = Arrays.copyOf(mode, n);
            raw = Arrays.copyOf(raw, n);
            pal = Arrays.copyOf(pal, n);
            vals = Arrays.copyOf(vals, n);
            size = Arrays.copyOf(size, n);
            cnt = Arrays.copyOf(cnt, n);
            opened = Arrays.copyOf(opened, n);
        }
        nSec = sectionCount;
        Arrays.fill(mode, 0, sectionCount, UNSEEN);
        lastSec = -1;
        lastState = null;
        this.h0 = h0;
        this.h1 = h1;
        op0 = t0.isOpaque();
        op1 = t1.isOpaque();
        Arrays.fill(done0, 0L);
        Arrays.fill(done1, 0L);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                cur0[(z << 4) | x] = h0.getFirstAvailable(x, z);
                cur1[(z << 4) | x] = h1.getFirstAvailable(x, z);
            }
        if (VERIFY) {
            this.chunk = chunk;
            vn = 0;
            Arrays.fill(opened, 0, sectionCount, false);
            int min = chunk.getMinBuildHeight();
            hFresh = true;
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    if (h0.getFirstAvailable(x, z) != min || h1.getFirstAvailable(x, z) != min) hFresh = false;
        }
    }

    /** vanilla's levelchunksection.setBlockState(x, y, z, state, false) for doFill (x, y, z section-relative) */
    public void set(int si, LevelChunkSection section, int x, int y, int z, BlockState state) {
        if (VERIFY) {
            if (vn == vPos.length) {
                vPos = Arrays.copyOf(vPos, vn * 2);
                vY = Arrays.copyOf(vY, vn * 2);
                vState = Arrays.copyOf(vState, vn * 2);
            }
            vPos[vn] = (si << 12) | (y << 8) | (z << 4) | x;
            vState[vn] = state;
        }
        int m = mode[si];
        if (m == FAST) {
            int id;
            if (si == lastSec && state == lastState) {
                id = lastId;
            } else {
                id = idFor(si, state);
                if (id < 0) {
                    section.setBlockState(x, y, z, state, false);
                    return;
                }
            }
            int idx = (y << 8) | (z << 4) | x;
            raw[si][idx >>> 4] |= (long) id << ((idx & 15) << 2);
            cnt[si][id]++;
            return;
        }
        if (m == UNSEEN && open(si, section)) {
            set(si, section, x, y, z, state);
            return;
        }
        section.setBlockState(x, y, z, state, false);
    }

    /** the state's id in the section's palette (appended in first-write order), or -1 after switching it to vanilla */
    private int idFor(int si, BlockState state) {
        BlockState[] v = vals[si];
        int n = size[si];
        for (int i = 0; i < n; i++) {
            if (v[i] == state) {
                lastSec = si;
                lastState = state;
                lastId = i;
                return i;
            }
        }
        if (n == 16) {
            // the 17th state: vanilla's resize from here on, over vanilla's state
            close(si);
            mode[si] = VANILLA;
            if (lastSec == si) lastSec = -1;
            return -1;
        }
        int id = pal[si].idFor(state);
        if (id != n) throw new IllegalStateException("FillWrite: palette id " + id + " != " + n);
        v[n] = state;
        size[si] = n + 1;
        lastSec = si;
        lastState = state;
        lastId = id;
        return id;
    }

    private boolean open(int si, LevelChunkSection section) {
        PalettedContainer<BlockState> states = section.getStates();
        PalettedContainer.Data<BlockState> d = states.data;
        if (!(d.palette() instanceof SingleValuePalette<BlockState> p) || !(d.storage() instanceof ZeroBitStorage) || p.valueFor(0) != AIR
                || section.nonEmptyBlockCount != 0 || section.tickingBlockCount != 0 || section.tickingFluidCount != 0) {
            mode[si] = VANILLA;
            return false;
        }
        // onResize(1, state) without copyFrom: Linear 4 bits, fresh zero storage, AIR first
        PalettedContainer.Data<BlockState> nd = states.createOrReuseData(d, 1);
        if (!(nd.palette() instanceof LinearPalette<BlockState>) || !(nd.storage() instanceof SimpleBitStorage s) || s.getBits() != 4) {
            mode[si] = VANILLA;
            return false;
        }
        if (nd.palette().idFor(AIR) != 0) throw new IllegalStateException("FillWrite: AIR not at 0");
        states.data = nd;
        sec[si] = section;
        raw[si] = s.getRaw();
        pal[si] = nd.palette();
        BlockState[] v = vals[si];
        if (v == null) vals[si] = v = new BlockState[16];
        v[0] = AIR;
        Arrays.fill(v, 1, 16, null);
        size[si] = 1;
        int[] c = cnt[si];
        if (c == null) cnt[si] = c = new int[16];
        Arrays.fill(c, 0);
        mode[si] = FAST;
        if (VERIFY) opened[si] = true;
        if (section instanceof BlockCollisionMemo.Counted mc) mc.sleep$touch();
        return true;
    }

    /** sets the section's counts from the writes so far (the section was fresh: all counts started at 0) */
    private void close(int si) {
        LevelChunkSection s = sec[si];
        BlockState[] v = vals[si];
        int[] c = cnt[si];
        int nonEmpty = 0, ticking = 0, fluid = 0;
        for (int i = 1, n = size[si]; i < n; i++) {
            int k = c[i];
            if (k == 0) continue;
            BlockState st = v[i];
            if (!st.isAir()) {
                nonEmpty += k;
                if (st.isRandomlyTicking()) ticking += k;
            }
            if (!st.getFluidState().isEmpty()) fluid += k;
        }
        s.nonEmptyBlockCount = (short) nonEmpty;
        s.tickingBlockCount = (short) ticking;
        s.tickingFluidCount = (short) fluid;
    }

    private void closeAll() {
        for (int si = 0; si < nSec; si++) if (mode[si] == FAST) close(si);
    }

    /**
     * vanilla's heightmap.update + heightmap1.update for a doFill write (x, z section-relative, y absolute). update with
     * i = first available: y <= i - 2 -> nothing; an opaque state at y >= i -> height y + 1; a non-opaque one at
     * y == i - 1 -> a scan down the chunk (the one case that reads blocks); anything else -> nothing. Here the same
     * cases on the column's i (read at the start, read again after an update that ran); after the first opaque state
     * nothing can happen in the column any more (every later write is lower) - done.
     */
    public void heights(int x, int y, int z, BlockState state) {
        if (VERIFY) vY[vn++] = y;
        int col = (z << 4) | x;
        long bit = 1L << col;
        int w = col >>> 6;
        if ((done0[w] & bit) == 0) {
            int c = cur0[col];
            if (op0.test(state)) {
                if (y >= c && !BREAK) h0.update(x, y, z, state);
                done0[w] |= bit;
            } else if (y == c - 1) {
                closeAll();
                h0.update(x, y, z, state);
                cur0[col] = h0.getFirstAvailable(x, z);
            }
        }
        if ((done1[w] & bit) == 0) {
            int c = cur1[col];
            if (op1.test(state)) {
                if (y >= c) h1.update(x, y, z, state);
                done1[w] |= bit;
            } else if (y == c - 1) {
                closeAll();
                h1.update(x, y, z, state);
                cur1[col] = h1.getFirstAvailable(x, z);
            }
        }
    }

    /** end of doFill: every section written here gets its counts */
    public void end() {
        closeAll();
        if (VERIFY) {
            try {
                verify();
            } catch (RuntimeException e) {
                V_BAD.incrementAndGet();
                System.out.println("[sleep/opt] FillWrite verify failed with " + e);
            }
            chunk = null;
            Arrays.fill(vState, 0, vn, null);
        }
        Arrays.fill(sec, 0, nSec, null);
        Arrays.fill(pal, 0, nSec, null);
        Arrays.fill(raw, 0, nSec, null);
        for (int si = 0; si < nSec; si++) if (vals[si] != null) Arrays.fill(vals[si], null);
        lastState = null;
        lastSec = -1;
        h0 = h1 = null;
        op0 = op1 = null;
    }

    private static LevelChunkSection fresh(LevelChunkSection like) {
        return new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES), like.getBiomes());
    }

    private void verify() {
        LevelChunkSection[] ref = new LevelChunkSection[nSec];
        Heightmap r0 = hFresh ? new Heightmap(chunk, Heightmap.Types.OCEAN_FLOOR_WG) : null;
        Heightmap r1 = hFresh ? new Heightmap(chunk, Heightmap.Types.WORLD_SURFACE_WG) : null;
        for (int k = 0; k < vn; k++) {
            int p = vPos[k], si = p >>> 12;
            BlockState st = vState[k];
            if (opened[si]) {
                LevelChunkSection r = ref[si];
                if (r == null) ref[si] = r = fresh(sec[si]);
                r.setBlockState(p & 15, (p >>> 8) & 15, (p >>> 4) & 15, st, false);
            }
            if (r0 != null) {
                r0.update(p & 15, vY[k], (p >>> 4) & 15, st);
                r1.update(p & 15, vY[k], (p >>> 4) & 15, st);
            }
        }
        long bad = 0, n = 0;
        for (int si = 0; si < nSec; si++) {
            if (mode[si] == VANILLA && !opened[si]) V_VANILLA.incrementAndGet();
            if (!opened[si]) continue;
            n++;
            LevelChunkSection a = sec[si], b = ref[si];
            if (b == null) b = fresh(a);
            String why = diff(a, b);
            if (why != null) {
                bad++;
                if (V_BAD.get() + bad < 20) System.out.println("[sleep/opt] FillWrite verify: section " + si + " of " + chunk.getPos() + " differs: " + why);
            }
        }
        V_SECTIONS.addAndGet(n);
        V_BAD.addAndGet(bad);
        if (r0 != null) {
            V_HEIGHTS.incrementAndGet();
            if (!Arrays.equals(r0.getRawData(), h0.getRawData()) || !Arrays.equals(r1.getRawData(), h1.getRawData())) {
                if (V_HBAD.incrementAndGet() < 20) System.out.println("[sleep/opt] FillWrite verify: heightmaps of " + chunk.getPos() + " differ");
            }
        }
        long c = V_CHUNKS.incrementAndGet();
        if ((c & 255) == 0) System.out.println("[sleep/opt] FillWrite verify: " + c + " chunks, " + V_SECTIONS.get() + " sections compared, "
                + V_BAD.get() + " differ; heightmaps " + V_HEIGHTS.get() + " chunks, " + V_HBAD.get() + " differ; " + V_VANILLA.get()
                + " sections not fresh (vanilla writes)");
    }

    private static String diff(LevelChunkSection a, LevelChunkSection b) {
        if (a.nonEmptyBlockCount != b.nonEmptyBlockCount || a.tickingBlockCount != b.tickingBlockCount || a.tickingFluidCount != b.tickingFluidCount)
            return "counts " + a.nonEmptyBlockCount + "/" + a.tickingBlockCount + "/" + a.tickingFluidCount + " vs " + b.nonEmptyBlockCount + "/"
                    + b.tickingBlockCount + "/" + b.tickingFluidCount;
        PalettedContainer.Data<BlockState> da = a.getStates().data, db = b.getStates().data;
        if (da.palette().getClass() != db.palette().getClass()) return "palette class " + da.palette().getClass() + " vs " + db.palette().getClass();
        if (da.palette().getSize() != db.palette().getSize()) return "palette size " + da.palette().getSize() + " vs " + db.palette().getSize();
        for (int i = 0; i < da.palette().getSize(); i++)
            if (da.palette().valueFor(i) != db.palette().valueFor(i)) return "palette entry " + i;
        if (da.storage().getBits() != db.storage().getBits()) return "bits " + da.storage().getBits() + " vs " + db.storage().getBits();
        if (!Arrays.equals(da.storage().getRaw(), db.storage().getRaw())) return "storage";
        return null;
    }
}
