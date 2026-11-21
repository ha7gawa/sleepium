package dev.lull.optsupport;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * SurfaceFast (-Dlab.opt.SurfaceFastMixin, 11-21, lab): SurfaceSystem.buildSurface's column loop over a snapshot of the
 * column, with the root plan's answers taken before the context is updated, and lean writes.
 *
 * <p>Vanilla walks every column from the top down to the bottom of the world, reading each block through
 * ProtoChunk.getBlockState (and the run of stone below it once per run); for each stone block it updates the rule
 * context (updateY) and, when the block is the default block, applies the rule tree; a non-null result is written
 * through ProtoChunk.setBlockState (section write, light check, the status's heightmaps by EnumSet and map lookups).
 *
 * <p>Here, per column (after the eroded badlands extension, which may write into it, exactly where vanilla does it):
 * <ul>
 * <li>the column's states are read once, straight from each section's palette and storage, with ProtoChunk.getBlockState's
 * own answers (AIR for a section with only air, VOID_AIR outside the build height). Vanilla's reads in the loop are of
 * positions not yet written (the loop writes only the block it is at, and reads it and the blocks below), so they are
 * the snapshot's values;</li>
 * <li>the loop's state (stone depth above, the water height, the bottom of the stone run) is kept exactly as vanilla
 * keeps it, over the snapshot;</li>
 * <li>updateY is called only for blocks the rule tree is applied to. A stone block that is not the default block gets
 * updateY and nothing else in vanilla; the next block the tree runs for gets its own updateY first, which sets every
 * field it sets and moves the y counter on (lazy conditions compare the counter for equality; it never repeats) - so
 * that skipped call has no effect;</li>
 * <li>with SurfacePlan on the root sequence (SurfacePlanMixin), the plan is asked first: vanilla's tryApply asks it as
 * its first act and returns null or the planned state without evaluating anything (the plan depends on y and the
 * column's min surface level only) - here the same answer without the updateY before it;</li>
 * <li>a write is ProtoChunk.setBlockState for a chunk whose status is before INITIALIZE_LIGHT (no light updates) and
 * whose status's heightmaps all exist (no priming): the section write (unlocked, as ProtoSetNoLockMixin), then the same
 * heightmaps' update in the same order; then BlockColumn.setBlock's post-processing mark for a block with a fluid.</li>
 * </ul>
 * Any other chunk (another class, a later status, a heightmap missing) runs vanilla's loop.
 *
 * <p>-Dlab.surface.verify: every chunk is built by vanilla's loop first, its sections, heightmaps and post-processing
 * marks saved and the chunk put back as it was; then our loop runs and the results are compared.
 */
public final class SurfaceWriter {
    public static final boolean VERIFY = Boolean.getBoolean("lab.surface.verify");
    /**
     * with SurfaceFast on, WorldgenAllocSequenceMixin calls its rules' tryApply directly (SurfaceRule is public by our
     * access transformer) instead of through SurfaceRuleInvoker's injected interface method: one interface dispatch per
     * rule instead of two
     */
    public static final boolean DIRECT = Boolean.getBoolean("lab.opt.SurfaceFastMixin");
    /** negative control for the verify: drop the planned write at y = min + 5 */
    private static final boolean BREAK = Boolean.getBoolean("lab.surface.break");
    private static final ThreadLocal<SurfaceWriter> TL = ThreadLocal.withInitial(SurfaceWriter::new);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), VOID_AIR = Blocks.VOID_AIR.defaultBlockState();
    private static final FluidState EMPTY = Fluids.EMPTY.defaultFluidState();
    private static final AtomicLong V_CHUNKS = new AtomicLong(), V_BAD = new AtomicLong(), V_SKIP = new AtomicLong();

    /** -Dlab.surface.stats: how deep below the local surface the blocks the rule tree runs for are */
    private static final boolean STATS = Boolean.getBoolean("lab.surface.stats");
    private static final AtomicLong S_CALLS = new AtomicLong();
    private static final java.util.concurrent.atomic.LongAdder S_NULL = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder[] S_ABOVE = new java.util.concurrent.atomic.LongAdder[8], S_BOTH = new java.util.concurrent.atomic.LongAdder[8];
    private static final java.util.concurrent.atomic.LongAdder S_WATER = new java.util.concurrent.atomic.LongAdder();
    static {
        for (int i = 0; i < 8; i++) {
            S_ABOVE[i] = new java.util.concurrent.atomic.LongAdder();
            S_BOTH[i] = new java.util.concurrent.atomic.LongAdder();
        }
    }

    private static void stat(int above, int below, int water) {
        long n = S_CALLS.incrementAndGet();
        if (water != Integer.MIN_VALUE) S_WATER.increment();
        int a = above <= 4 ? 0 : above <= 8 ? 1 : above <= 16 ? 2 : above <= 32 ? 3 : above <= 40 ? 4 : above <= 64 ? 5 : above <= 128 ? 6 : 7;
        S_ABOVE[a].increment();
        int m = Math.min(above, below);
        int b = m <= 4 ? 0 : m <= 8 ? 1 : m <= 16 ? 2 : m <= 32 ? 3 : m <= 40 ? 4 : m <= 64 ? 5 : m <= 128 ? 6 : 7;
        S_BOTH[b].increment();
        if ((n & 0xFFFFF) == 0) {
            StringBuilder sb = new StringBuilder("[sleep/opt] SurfaceFast stats: tree calls " + n + ", null " + S_NULL.sum() + ", with water above "
                    + S_WATER.sum() + "; stone depth above <=4/8/16/32/40/64/128/more:");
            for (int i = 0; i < 8; i++) sb.append(' ').append(S_ABOVE[i].sum());
            sb.append("; min(above, below):");
            for (int i = 0; i < 8; i++) sb.append(' ').append(S_BOTH[i].sum());
            System.out.println(sb);
        }
    }

    public static SurfaceWriter get() {
        return TL.get();
    }

    private ProtoChunk chunk;
    private LevelChunkSection[] sections;
    private Heightmap[] hms = new Heightmap[8];
    private int nh;
    private int minY, maxY;
    private BlockState def;
    private SurfacePlan plan;
    /** SurfaceTree (lab.opt.SurfaceTree): the chunk's tree with its stone depth tests decided per block, or null */
    private SurfaceTree tree;
    /**
     * per snapshot entry (index y - minY + 1): bits 0-1 the class - 0 air (isAir), 1 a block with a fluid, 2 stone in
     * SurfaceSystem.isStone's sense; bit 2 (DEF): the state is the default block
     */
    private byte[] cls = new byte[512];
    private static final int DEF = 4;
    // per section: the Data and palette size its id table was made for, the table (flags by palette id)
    @SuppressWarnings("unchecked")
    private PalettedContainer.Data<BlockState>[] sData = new PalettedContainer.Data[32];
    private int[] sSize = new int[32];
    private byte[][] sFlags = new byte[32][];
    /** per heightmap: the current column's first available y, as Heightmap.getFirstAvailable says */
    private int[] cur = new int[8];
    // write: the last palette id asked for, valid while the container's Data is the same object
    private PalettedContainer.Data<BlockState> wData;
    private BlockState wState;
    private int wId;
    private long[] wRaw;
    private int wNonEmpty, wTicking, wFluid;
    private boolean wMark;
    private final BlockPos.MutableBlockPos mark = new BlockPos.MutableBlockPos();

    private SurfaceWriter() {}

    /** vanilla's BlockColumn of buildSurface (SurfaceSystem$1) */
    public static final class Column implements BlockColumn {
        private final ChunkAccess chunk;
        private final BlockPos.MutableBlockPos pos;

        public Column(ChunkAccess chunk, BlockPos.MutableBlockPos pos) {
            this.chunk = chunk;
            this.pos = pos;
        }

        @Override
        public BlockState getBlock(int y) {
            return chunk.getBlockState(pos.setY(y));
        }

        @Override
        public void setBlock(int y, BlockState state) {
            LevelHeightAccessor h = chunk.getHeightAccessorForGeneration();
            if (y >= h.getMinBuildHeight() && y < h.getMaxBuildHeight()) {
                chunk.setBlockState(pos.setY(y), state, false);
                if (!state.getFluidState().isEmpty()) chunk.markPosForPostprocessing(pos);
            }
        }

        @Override
        public String toString() {
            return "ChunkBlockColumn " + chunk.getPos();
        }
    }

    /** whether this chunk takes our loop; sets up the chunk's state */
    public boolean begin(ChunkAccess c, SurfaceRules.SurfaceRule rule, BlockState defaultBlock, SurfaceRules.Context ctx) {
        if (c.getClass() != ProtoChunk.class) return false;
        ProtoChunk pc = (ProtoChunk) c;
        ChunkStatus st = pc.getPersistedStatus();
        if (st.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) return false;
        EnumSet<Heightmap.Types> after = st.heightmapsAfter();
        if (after.size() > hms.length) {
            hms = new Heightmap[after.size()];
            cur = new int[after.size()];
        }
        nh = 0;
        for (Heightmap.Types t : after) {
            if (!pc.hasPrimedHeightmap(t)) return false;
            hms[nh++] = pc.getOrCreateHeightmapUnprimed(t);
        }
        if (pc.getHeightAccessorForGeneration() != pc) return false;
        chunk = pc;
        sections = pc.getSections();
        minY = pc.getMinBuildHeight();
        maxY = pc.getMaxBuildHeight();
        if (maxY - minY + 2 > cls.length) cls = new byte[maxY - minY + 2];
        int ns = sections.length;
        if (ns > sData.length) {
            sData = Arrays.copyOf(sData, ns);
            sSize = Arrays.copyOf(sSize, ns);
            sFlags = Arrays.copyOf(sFlags, ns);
        }
        Arrays.fill(sData, 0, ns, null);
        def = defaultBlock;
        plan = rule instanceof SurfacePlan.Holder h ? h.sleep$planOrNull() : null;
        tree = SurfaceTree.ON ? SurfaceTree.of(rule, ctx) : null;
        return true;
    }

    public void end() {
        chunk = null;
        sections = null;
        Arrays.fill(hms, null);
        plan = null;
        tree = null;
        Arrays.fill(sData, null);
        wData = null;
        wState = null;
        wRaw = null;
    }

    /**
     * One column of vanilla's loop (x, z block coordinates, k, l their chunk-relative ones; top = the first y the loop
     * reads), after ctx.updateXZ.
     */
    public void column(int k, int l, int top, int x, int z, SurfaceRules.Context ctx, SurfaceRules.SurfaceRule rule) {
        byte[] cls = this.cls;
        int minY = this.minY, base = 1 - minY;
        cls[0] = 0;
        int hi = Math.min(top, maxY - 1);
        for (int y = top; y > hi; y--) cls[y + base] = 0;
        int xz = (l << 4) | k;
        for (int s = 0, sTop = (hi - minY) >> 4; s <= sTop; s++) {
            LevelChunkSection sec = sections[s];
            int y0 = minY + (s << 4), y1 = Math.min(y0 + 15, hi);
            if (sec.hasOnlyAir()) {
                Arrays.fill(cls, y0 + base, y1 + base + 1, (byte) 0);
                continue;
            }
            PalettedContainer.Data<BlockState> d = sec.getStates().data;
            Palette<BlockState> p = d.palette();
            BitStorage b = d.storage();
            if (p instanceof GlobalPalette) {
                for (int y = y0; y <= y1; y++) cls[y + base] = flags(p.valueFor(b.get(((y & 15) << 8) | xz)));
                continue;
            }
            int size = p.getSize();
            byte[] f = sFlags[s];
            if (d != sData[s] || size != sSize[s]) {
                if (f == null || f.length < size) sFlags[s] = f = new byte[Math.max(size, 16)];
                for (int id = 0; id < size; id++) f[id] = flags(p.valueFor(id));
                sData[s] = d;
                sSize[s] = size;
            }
            int bits = b.getBits();
            if (bits == 0) {
                Arrays.fill(cls, y0 + base, y1 + base + 1, f[0]);
            } else if (bits == 4) {
                long[] raw = b.getRaw();
                for (int y = y0; y <= y1; y++) {
                    int idx = ((y & 15) << 8) | xz;
                    cls[y + base] = f[(int) (raw[idx >>> 4] >>> ((idx & 15) << 2)) & 15];
                }
            } else if (bits == 8) {
                long[] raw = b.getRaw();
                for (int y = y0; y <= y1; y++) {
                    int idx = ((y & 15) << 8) | xz;
                    cls[y + base] = f[(int) (raw[idx >>> 3] >>> ((idx & 7) << 3)) & 255];
                }
            } else {
                for (int y = y0; y <= y1; y++) cls[y + base] = f[b.get(((y & 15) << 8) | xz)];
            }
        }
        for (int h = 0; h < nh; h++) cur[h] = hms[h].getFirstAvailable(k, l);

        SurfacePlan plan = this.plan;
        SurfaceTree tree = this.tree;
        boolean treeColumn = false;
        int i2 = 0, j2 = Integer.MIN_VALUE, k2 = Integer.MAX_VALUE;
        for (int y = top; y >= minY; y--) {
            int c = cls[y + base] & 3;
            if (c == 0) {
                i2 = 0;
                j2 = Integer.MIN_VALUE;
                continue;
            }
            if (c == 1) {
                if (j2 == Integer.MIN_VALUE) j2 = y + 1;
                continue;
            }
            if (k2 >= y) {
                k2 = DimensionType.WAY_BELOW_MIN_Y;
                for (int j3 = y - 1; j3 >= minY - 1; j3--) {
                    if ((cls[j3 + base] & 3) != 2) {
                        k2 = j3 + 1;
                        break;
                    }
                }
            }
            i2++;
            if ((cls[y + base] & DEF) == 0) continue;
            int start = 0;
            if (plan != null) {
                int e = plan.eval(y);
                start = e;
                if (e == SurfacePlan.NULL) continue;
                if (e == SurfacePlan.STATE) {
                    if (BREAK && y == minY + 5) continue;
                    write(k, y, l, x, z, plan.state());
                    continue;
                }
            }
            if (STATS) stat(i2, y - k2 + 1, j2);
            BlockState r;
            if (tree != null) {
                if (!treeColumn) {
                    tree.column();
                    treeColumn = true;
                }
                ctx.updateY(i2, y - k2 + 1, j2, x, y, z);
                r = tree.apply(start, i2, y - k2 + 1, x, y, z);
            } else {
                ctx.updateY(i2, y - k2 + 1, j2, x, y, z);
                r = rule.tryApply(x, y, z);
            }
            if (STATS && r == null) S_NULL.increment();
            if (r != null) write(k, y, l, x, z, r);
        }
    }

    private byte flags(BlockState st) {
        int c = st.isAir() ? 0 : st.getFluidState().isEmpty() ? 2 : 1;
        return (byte) (st == def ? c | DEF : c);
    }

    /**
     * BlockColumn.setBlock -> ProtoChunk.setBlockState(pos, state, false) for this chunk (see the class doc). The section
     * write is LevelChunkSection.setBlockState(.., false) -> PalettedContainer.getAndSetUnchecked inlined. The old state
     * is the default block: the loop writes only where the snapshot (taken at the column's start) has it, and nothing
     * else writes the column between the snapshot and the write (each y once; a resize copies the value). So: the
     * palette id (the last one asked for while the container's Data is the same object - ids never change within a
     * Data; idFor may resize, so the Data is read again after it, as vanilla's getAndSet reads this.data again), the
     * storage set (4 bits straight into the raw long), the counts changed by (state's - default block's) non-empty /
     * ticking / fluid terms, made once per state. A heightmap's update returns at its first test when y <= first
     * available - 2: that test made here on the column's heights (read at the column's start, read again after every
     * update this loop makes - nothing else writes the column meanwhile).
     */
    private void write(int k, int y, int l, int x, int z, BlockState state) {
        LevelChunkSection s = sections[(y - minY) >> 4];
        if (state == def) {
            // the block is the default block already: vanilla's section write changes nothing (the palette has it, the
            // storage keeps its id, the counts go -1 +1); its heightmap updates are kept
            for (int h = 0; h < nh; h++) {
                if (y <= cur[h] - 2) continue;
                hms[h].update(k, y, l, state);
                cur[h] = hms[h].getFirstAvailable(k, l);
            }
            if (!state.getFluidState().isEmpty()) chunk.markPosForPostprocessing(mark.set(x, y, z));
            return;
        }
        if (!(s.hasOnlyAir() && state.is(Blocks.AIR))) {
            PalettedContainer<BlockState> pc = s.getStates();
            PalettedContainer.Data<BlockState> d = pc.data;
            if (d != wData || state != wState) {
                int id = d.palette().idFor(state);
                d = pc.data;
                wData = d;
                wId = id;
                BitStorage b = d.storage();
                wRaw = b.getBits() == 4 && b instanceof SimpleBitStorage ? b.getRaw() : null;
                if (state != wState) {
                    wState = state;
                    BlockState o = def;
                    wNonEmpty = (state.isEmpty() ? 0 : 1) - (o.isEmpty() ? 0 : 1);
                    wTicking = (!state.isEmpty() && state.isRandomlyTicking() ? 1 : 0) - (!o.isEmpty() && o.isRandomlyTicking() ? 1 : 0);
                    wFluid = (state.getFluidState().isEmpty() ? 0 : 1) - (o.getFluidState().isEmpty() ? 0 : 1);
                    wMark = !state.getFluidState().isEmpty();
                }
            }
            int idx = ((y & 15) << 8) | (l << 4) | k;
            long[] raw = wRaw;
            if (raw != null) {
                int c = idx >>> 4, sh = (idx & 15) << 2;
                raw[c] = (raw[c] & ~(15L << sh)) | ((long) wId << sh);
            } else {
                d.storage().set(idx, wId);
            }
            if (s instanceof BlockCollisionMemo.Counted mc) mc.sleep$touch();
            if (wNonEmpty != 0) s.nonEmptyBlockCount += (short) wNonEmpty;
            if (wTicking != 0) s.tickingBlockCount += (short) wTicking;
            if (wFluid != 0) s.tickingFluidCount += (short) wFluid;
            for (int h = 0; h < nh; h++) {
                if (y <= cur[h] - 2) continue;
                hms[h].update(k, y, l, state);
                cur[h] = hms[h].getFirstAvailable(k, l);
            }
            if (wMark) chunk.markPosForPostprocessing(mark.set(x, y, z));
            return;
        }
        if (!state.getFluidState().isEmpty()) chunk.markPosForPostprocessing(mark.set(x, y, z));
    }

    // ---- verify ----

    /** the chunk's sections, heightmaps and post-processing marks */
    public static final class Saved {
        PalettedContainer.Data<BlockState>[] data;
        short[] counts;
        long[][] heights;
        Heightmap.Types[] types;
        short[][] post;
    }

    @SuppressWarnings("unchecked")
    public static Saved save(ChunkAccess c) {
        Saved s = new Saved();
        LevelChunkSection[] secs = c.getSections();
        s.data = new PalettedContainer.Data[secs.length];
        s.counts = new short[secs.length * 3];
        for (int i = 0; i < secs.length; i++) {
            s.data[i] = secs[i].getStates().data.copy();
            s.counts[i * 3] = secs[i].nonEmptyBlockCount;
            s.counts[i * 3 + 1] = secs[i].tickingBlockCount;
            s.counts[i * 3 + 2] = secs[i].tickingFluidCount;
        }
        Heightmap.Types[] all = Heightmap.Types.values();
        s.types = new Heightmap.Types[all.length];
        s.heights = new long[all.length][];
        for (int i = 0; i < all.length; i++) {
            if (c.hasPrimedHeightmap(all[i])) {
                s.types[i] = all[i];
                s.heights[i] = c.getOrCreateHeightmapUnprimed(all[i]).getRawData().clone();
            }
        }
        ShortList[] pp = c.getPostProcessing();
        s.post = new short[pp.length][];
        for (int i = 0; i < pp.length; i++) s.post[i] = pp[i] == null ? null : pp[i].toShortArray();
        return s;
    }

    public static void restore(ChunkAccess c, Saved s) {
        LevelChunkSection[] secs = c.getSections();
        for (int i = 0; i < secs.length; i++) {
            secs[i].getStates().data = s.data[i].copy();
            secs[i].nonEmptyBlockCount = s.counts[i * 3];
            secs[i].tickingBlockCount = s.counts[i * 3 + 1];
            secs[i].tickingFluidCount = s.counts[i * 3 + 2];
        }
        for (int i = 0; i < s.types.length; i++)
            if (s.types[i] != null) c.getOrCreateHeightmapUnprimed(s.types[i]).setRawData(c, s.types[i], s.heights[i].clone());
        ShortList[] pp = c.getPostProcessing();
        for (int i = 0; i < pp.length; i++) {
            if (pp[i] == null) continue;
            pp[i].clear();
            if (s.post[i] != null) for (short v : s.post[i]) pp[i].add(v);
        }
    }

    /** compares the chunk now with a saved vanilla result; counts and logs */
    public static void compare(ChunkAccess c, Saved ref) {
        Saved now = save(c);
        String why = null;
        for (int i = 0; i < now.data.length && why == null; i++) {
            PalettedContainer.Data<BlockState> a = now.data[i], b = ref.data[i];
            if (now.counts[i * 3] != ref.counts[i * 3] || now.counts[i * 3 + 1] != ref.counts[i * 3 + 1] || now.counts[i * 3 + 2] != ref.counts[i * 3 + 2])
                why = "section " + i + " counts";
            else if (a.palette().getClass() != b.palette().getClass() || a.palette().getSize() != b.palette().getSize())
                why = "section " + i + " palette " + a.palette().getClass().getSimpleName() + "/" + a.palette().getSize() + " vs "
                        + b.palette().getClass().getSimpleName() + "/" + b.palette().getSize();
            else {
                for (int p = 0; p < a.palette().getSize(); p++)
                    if (a.palette().valueFor(p) != b.palette().valueFor(p)) {
                        why = "section " + i + " palette entry " + p;
                        break;
                    }
                if (why == null && (a.storage().getBits() != b.storage().getBits() || !Arrays.equals(a.storage().getRaw(), b.storage().getRaw())))
                    why = "section " + i + " storage";
            }
        }
        for (int i = 0; i < now.types.length && why == null; i++) {
            if ((now.types[i] == null) != (ref.types[i] == null)) why = "heightmap presence " + i;
            else if (now.types[i] != null && !Arrays.equals(now.heights[i], ref.heights[i])) why = "heightmap " + now.types[i];
        }
        for (int i = 0; i < now.post.length && why == null; i++)
            if (!Arrays.equals(now.post[i], ref.post[i])) why = "post-processing list " + i;
        if (why != null && V_BAD.incrementAndGet() < 20) System.out.println("[sleep/opt] SurfaceFast verify: " + c.getPos() + " differs: " + why);
        long n = V_CHUNKS.incrementAndGet();
        if ((n & 255) == 0) System.out.println("[sleep/opt] SurfaceFast verify: " + n + " chunks compared, " + V_BAD.get() + " differ, "
                + V_SKIP.get() + " took vanilla's loop");
    }

    public static void skipped() {
        if (VERIFY) V_SKIP.incrementAndGet();
    }
}
