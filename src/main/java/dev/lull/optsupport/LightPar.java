package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * LightPar (-Dlab.opt.LightPar, notes/84): the increase pass of a server light batch (LightEngine.propagateIncreases,
 * block and sky alike) computed by several threads, with the light values vanilla's serial pass produces.
 *
 * <p>Why the result is vanilla's. After the decrease pass, the increase pass only raises values: an entry (pos, level,
 * directions) is processed when the stored value still equals its level, and a neighbour is raised to level - opacity
 * when that is higher and the faces do not occlude - edge costs that depend on blocks and on which sections store light,
 * both fixed during the pass. Such a max-plus relaxation reaches the same values in any processing order: every value is
 * the best seed level minus the cheapest path's cost (skipped entries are always dominated by a higher entry of the same
 * cell, and an entry's left-out directions point at cells that are at least as bright). One exception in vanilla: the
 * sky's light falling through sections that store nothing (propagateFromEmptySections) writes the level below
 * unconditionally, which could LOWER a cell another path made brighter - order dependent. Those writes are recorded;
 * if any of them lowered a value here or its cell ends brighter than the level written, the batch is thrown away and
 * vanilla's serial pass runs instead (every value here was made in private copies, nothing is published before the end).
 *
 * <p>Why it can run in parallel. Light from a seed travels at most 14 blocks (level 15, each step costs at least 1), so
 * the work seeded in chunk C reads and writes light only in the 3 x 3 chunks around C. Seeds are grouped by chunk; groups
 * whose chunk coordinates are equal mod 3 (9 colours) have disjoint 3 x 3 areas and run at the same time, colour after
 * colour. A group's sections are copied once (vanilla copies a section on its first write of a batch too) into the
 * batch's overlay; at the end the overlay replaces the storage's layers (setLayer + changedSections, as copyDataLayer
 * would) and the affected sections are added for the light-update packets.
 *
 * <p>{@code -Dlab.lightpar.verify=true}: the parallel result is kept aside, vanilla's serial pass runs, and every section
 * of the batch's 3 x 3 areas is compared nibble by nibble (report: LightPar.report()).
 */
public final class LightPar {
    private LightPar() {}

    public static final boolean ENABLED = Boolean.getBoolean("lab.opt.LightPar");
    public static final boolean VERIFY = Boolean.getBoolean("lab.lightpar.verify");
    static final int MIN_ENTRIES = Integer.getInteger("lab.lightpar.minEntries", 2048);
    static final int HELPERS = Integer.getInteger("lab.lightpar.helpers", Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    static final Executor EXEC = Util.backgroundExecutor();
    /** a thread whose private engines (not the server's) use LightPar too - LightCheck's timed relight (measurement) */
    public static volatile Thread forced;

    public static final LongAdder PAR = new LongAdder(), SMALL = new LongAdder(), FALLBACK = new LongAdder(), ERRORS = new LongAdder(),
            GROUPS = new LongAdder(), ENTRIES = new LongAdder(), NANOS = new LongAdder(), HELPED = new LongAdder(),
            VERIFIED = new LongAdder(), VERIFY_SECTIONS = new LongAdder(), VERIFY_DIFF = new LongAdder(), VERIFY_NIBBLES = new LongAdder();

    /** the engine's parts (LightParMixin on LightEngine) */
    public interface Engine {
        LongArrayFIFOQueue sleep$increaseQueue();

        LightChunkGetter sleep$chunkSource();

        Long2ObjectOpenHashMap<DataLayer> sleep$updatingMap();

        void sleep$setLayer(long sec, DataLayer layer);

        LongSet sleep$changedSections();

        LongSet sleep$affectedSections();

        /** the sky storage's lowest section with data (hasLightDataAtOrBelow); unused for block light */
        int sleep$lowestY();
    }

    private static final Direction[] DIRS = Direction.values();

    public static String report() {
        return String.format(Locale.ROOT, "LightPar: %d batches parallel (%d groups, %d entries, %.0f ms, %d groups run by helpers), %d too small, %d fallbacks (order-dependent sky writes), %d errors;"
                        + " verify: %d batches, %d sections compared, %d differ (%d nibbles)",
                PAR.sum(), GROUPS.sum(), ENTRIES.sum(), NANOS.sum() / 1e6, HELPED.sum(), SMALL.sum(), FALLBACK.sum(), ERRORS.sum(),
                VERIFIED.sum(), VERIFY_SECTIONS.sum(), VERIFY_DIFF.sum(), VERIFY_NIBBLES.sum());
    }

    /** verification state handed from the HEAD of propagateIncreases to its RETURN */
    public static final class Pending {
        final Long2ObjectOpenHashMap<DataLayer> ours = new Long2ObjectOpenHashMap<>();
    }

    /**
     * The increase pass of one batch. Returns the entries processed, or -1 when vanilla's pass must run (the queue is
     * then exactly as it was). With verify on, always -1, the result kept in {@code pendingOut[0]}.
     */
    public static int run(Engine eng, boolean sky, Pending[] pendingOut) {
        LongArrayFIFOQueue q = eng.sleep$increaseQueue();
        int n = q.size();
        if (n < 2 * MIN_ENTRIES) {
            SMALL.increment();
            return -1;
        }
        long t0 = System.nanoTime();
        long[] ent = new long[n];
        for (int i = 0; i < n; i++) ent[i] = q.dequeueLong();
        try {
            Batch b = new Batch(eng, sky, ent);
            if (b.groups.size() < 2) {
                SMALL.increment();
                requeue(q, ent);
                return -1;
            }
            b.runAll();
            if (b.conflict) {
                FALLBACK.increment();
                requeue(q, ent);
                return -1;
            }
            PAR.increment();
            GROUPS.add(b.groups.size());
            ENTRIES.add(n / 2);
            NANOS.add(System.nanoTime() - t0);
            if (VERIFY) {
                Pending p = new Pending();
                // every section of the groups' 3 x 3 areas: ours (copy made) or the layer as it is now (untouched by us)
                b.forArea(sec -> {
                    DataLayer o = b.overlay.get(sec);
                    if (o == null) {
                        DataLayer cur = eng.sleep$updatingMap().get(sec);
                        o = cur == null ? null : cur.copy();
                    }
                    p.ours.put(sec, o);
                });
                pendingOut[0] = p;
                requeue(q, ent);
                return -1;
            }
            b.commit();
            return b.count;
        } catch (Throwable t) {
            ERRORS.increment();
            if (ERRORS.sum() <= 3) t.printStackTrace();
            q.clear();
            requeue(q, ent);
            return -1;
        }
    }

    /** after vanilla's serial pass (verify): compare its layers with ours */
    public static void verify(Engine eng, Pending p) {
        VERIFIED.increment();
        var map = eng.sleep$updatingMap();
        for (var e : p.ours.long2ObjectEntrySet()) {
            DataLayer v = map.get(e.getLongKey()), o = e.getValue();
            VERIFY_SECTIONS.increment();
            if (v == null && o == null) continue;
            int d = 0;
            if (v == null || o == null) d = 4096;
            else if (!v.isDefinitelyHomogenous() && !o.isDefinitelyHomogenous() && Arrays.equals(v.getData(), o.getData())) continue;
            else
                for (int i = 0; i < 4096; i++)
                    if (v.get(i & 15, i >> 8, (i >> 4) & 15) != o.get(i & 15, i >> 8, (i >> 4) & 15)) d++;
            if (d > 0) {
                if (VERIFY_DIFF.sum() < 5)
                    System.out.println("[sleep/opt] LightPar verify: section " + SectionPos.of(e.getLongKey()) + " differs in " + d + " nibbles");
                VERIFY_DIFF.increment();
                VERIFY_NIBBLES.add(d);
            }
        }
    }

    private static void requeue(LongArrayFIFOQueue q, long[] ent) {
        for (long v : ent) q.enqueue(v);
    }

    /** one batch: the groups (seeds by chunk), the overlay of copied sections */
    static final class Batch {
        final Engine eng;
        final boolean sky;
        final Long2ObjectOpenHashMap<DataLayer> map;
        final int lowestY;
        final LightChunkGetter chunks;
        final BlockGetter level;
        final ArrayList<Group> groups = new ArrayList<>();
        final Long2ObjectOpenHashMap<DataLayer> overlay = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet affected = new LongOpenHashSet();
        final LongArrayList emptyWrites = new LongArrayList();
        boolean conflict;
        int count;
        final int minSec, maxSec;

        Batch(Engine eng, boolean sky, long[] ent) {
            this.eng = eng;
            this.sky = sky;
            this.map = eng.sleep$updatingMap();
            this.lowestY = sky ? eng.sleep$lowestY() : 0;
            this.chunks = eng.sleep$chunkSource();
            this.level = chunks.getLevel();
            var hl = (net.minecraft.world.level.LevelHeightAccessor) level;
            this.minSec = hl.getMinSection() - 1;
            this.maxSec = hl.getMaxSection() + 1;
            Long2ObjectOpenHashMap<Group> byChunk = new Long2ObjectOpenHashMap<>();
            for (int i = 0; i < ent.length; i += 2) {
                long pos = ent[i];
                long ck = ChunkPos.asLong(SectionPos.blockToSectionCoord(BlockPos.getX(pos)), SectionPos.blockToSectionCoord(BlockPos.getZ(pos)));
                Group g = byChunk.get(ck);
                if (g == null) {
                    g = new Group(ck);
                    byChunk.put(ck, g);
                    groups.add(g);
                }
                g.seeds.add(pos);
                g.seeds.add(ent[i + 1]);
            }
        }

        void forArea(java.util.function.LongConsumer out) {
            LongOpenHashSet seen = new LongOpenHashSet();
            for (Group g : groups) {
                int cx = ChunkPos.getX(g.chunk), cz = ChunkPos.getZ(g.chunk);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        for (int sy = minSec; sy < maxSec; sy++) {
                            long s = SectionPos.asLong(cx + dx, sy, cz + dz);
                            if (seen.add(s)) out.accept(s);
                        }
            }
        }

        void runAll() throws InterruptedException {
            for (int color = 0; color < 9; color++) {
                ArrayList<Group> mine = new ArrayList<>();
                for (Group g : groups)
                    if (Math.floorMod(ChunkPos.getX(g.chunk), 3) * 3 + Math.floorMod(ChunkPos.getZ(g.chunk), 3) == color) mine.add(g);
                if (mine.isEmpty()) continue;
                Phase ph = new Phase(this, mine.toArray(new Group[0]));
                ph.runAll(Math.min(HELPERS, mine.size() - 1));
                if (ph.error != null) throw new RuntimeException("LightPar group failed", ph.error);
                // the phase's copies become the overlay the next colours read; results in group order
                for (Group g : mine) {
                    overlay.putAll(g.copies);
                    affected.addAll(g.affected);
                    emptyWrites.addAll(g.emptyWrites);
                    conflict |= g.conflict;
                    count += g.count;
                    g.copies = null;
                }
            }
            // order dependence check: every unconditional "through empty sections" write must have its level as the final value
            for (int i = 0; i < emptyWrites.size() && !conflict; i += 2) {
                long pos = emptyWrites.getLong(i);
                DataLayer l = overlay.get(SectionPos.blockToSection(pos));
                if (l == null) l = map.get(SectionPos.blockToSection(pos));
                if (l == null || nib(l, pos) != (int) emptyWrites.getLong(i + 1)) conflict = true;
            }
        }

        void commit() {
            LongSet changed = eng.sleep$changedSections();
            for (var e : overlay.long2ObjectEntrySet()) {
                eng.sleep$setLayer(e.getLongKey(), e.getValue());
                changed.add(e.getLongKey());
            }
            eng.sleep$affectedSections().addAll(affected);
        }
    }

    static int nib(DataLayer l, long pos) {
        return l.get(SectionPos.sectionRelative(BlockPos.getX(pos)), SectionPos.sectionRelative(BlockPos.getY(pos)), SectionPos.sectionRelative(BlockPos.getZ(pos)));
    }

    static final class Group {
        final long chunk;
        final LongArrayList seeds = new LongArrayList();
        Long2ObjectOpenHashMap<DataLayer> copies = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet affected = new LongOpenHashSet();
        final LongArrayList emptyWrites = new LongArrayList();
        boolean conflict;
        int count;

        Group(long chunk) {
            this.chunk = chunk;
        }
    }

    /** one colour: its groups taken by the light thread and helpers from a shared index */
    static final class Phase implements Runnable {
        final Batch b;
        final Group[] gs;
        final AtomicInteger next = new AtomicInteger(), active = new AtomicInteger();
        volatile Throwable error;
        final Thread owner = Thread.currentThread();

        Phase(Batch b, Group[] gs) {
            this.b = b;
            this.gs = gs;
        }

        @Override
        public void run() {
            active.incrementAndGet();
            try {
                Prop p = PROP.get();
                int i;
                while ((i = next.getAndIncrement()) < gs.length) {
                    if (Thread.currentThread() != owner) HELPED.increment();
                    p.run(b, gs[i]);
                }
            } catch (Throwable t) {
                error = t;
                next.set(gs.length);
            } finally {
                if (active.decrementAndGet() == 0) synchronized (this) { notifyAll(); }
            }
        }

        void runAll(int helpers) throws InterruptedException {
            for (int h = 0; h < helpers; h++) EXEC.execute(this);
            run();
            synchronized (this) {
                while (active.get() > 0) wait();
            }
        }
    }

    static final ThreadLocal<Prop> PROP = ThreadLocal.withInitial(Prop::new);

    /** a thread's propagation state: vanilla's increase pass for one group, block or sky */
    static final class Prop {
        final LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        final long[] chunkKeys = new long[9];
        final LightChunk[] chunkVals = new LightChunk[9];
        int chunkN;
        Batch b;
        Group g;
        long lastSec = Long.MIN_VALUE;
        DataLayer lastLayer;

        void run(Batch b, Group g) {
            this.b = b;
            this.g = g;
            chunkN = 0;
            lastSec = Long.MIN_VALUE;
            lastLayer = null;
            q.clear();
            LongArrayList s = g.seeds;
            for (int i = 0; i < s.size(); i++) q.enqueue(s.getLong(i));
            int count = 0;
            while (!q.isEmpty()) {
                long j = q.dequeueLong(), k = q.dequeueLong();
                count++;
                int l = stored(j);
                int i1 = LightEngine.QueueEntry.getFromLevel(k);
                if (LightEngine.QueueEntry.isIncreaseFromEmission(k) && l < i1) {
                    set(j, i1);
                    l = i1;
                }
                if (l == i1) {
                    if (b.sky) incSky(j, k, l);
                    else incBlock(j, k, l);
                }
            }
            g.count = count;
            Arrays.fill(chunkVals, null);
            this.b = null;
            this.g = null;
            lastLayer = null;
        }

        @Nullable
        DataLayer layer(long sec) {
            if (sec == lastSec) return lastLayer;
            DataLayer l = g.copies.get(sec);
            if (l == null) l = b.overlay.get(sec);
            if (l == null) l = b.map.get(sec);
            lastSec = sec;
            lastLayer = l;
            return l;
        }

        boolean storing(long sec) {
            return layer(sec) != null;
        }

        int stored(long pos) {
            return nib(layer(SectionPos.blockToSection(pos)), pos);
        }

        void set(long pos, int v) {
            long sec = SectionPos.blockToSection(pos);
            DataLayer l = g.copies.get(sec);
            if (l == null) {
                l = b.overlay.get(sec);   // copied by an earlier colour: batch-private, this group's alone now
                if (l == null) {
                    l = b.map.get(sec).copy();
                    g.copies.put(sec, l);
                    lastSec = Long.MIN_VALUE;
                }
            }
            l.set(SectionPos.sectionRelative(BlockPos.getX(pos)), SectionPos.sectionRelative(BlockPos.getY(pos)), SectionPos.sectionRelative(BlockPos.getZ(pos)), v);
            SectionPos.aroundAndAtBlockPos(pos, g.affected::add);
        }

        @Nullable
        LightChunk chunk(int x, int z) {
            long k = ChunkPos.asLong(x, z);
            for (int i = 0; i < chunkN; i++)
                if (chunkKeys[i] == k) return chunkVals[i];
            LightChunk c = b.chunks.getChunkForLighting(x, z);
            if (chunkN < 9) {
                chunkKeys[chunkN] = k;
                chunkVals[chunkN++] = c;
            }
            return c;
        }

        BlockState state(BlockPos pos) {
            LightChunk c = chunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
            return c == null ? Blocks.BEDROCK.defaultBlockState() : c.getBlockState(pos);
        }

        int opacity(BlockState s, BlockPos pos) {
            return Math.max(1, s.getLightBlock(b.level, pos));
        }

        static boolean emptyShape(BlockState s) {
            return !s.canOcclude() || !s.useShapeForLightOcclusion();
        }

        boolean occludes(long p1, BlockState s1, long p2, BlockState s2, Direction d) {
            VoxelShape a = LightEngine.getOcclusionShape(b.level, mp.set(p1), s1, d);
            VoxelShape c = LightEngine.getOcclusionShape(b.level, mp.set(p2), s2, d.getOpposite());
            return Shapes.faceShapeOccludes(a, c);
        }

        void incBlock(long pos, long entry, int level) {
            BlockState from = null;
            for (Direction d : DIRS) {
                if (!LightEngine.QueueEntry.shouldPropagateInDirection(entry, d)) continue;
                long j = BlockPos.offset(pos, d);
                if (!storing(SectionPos.blockToSection(j))) continue;
                int k = stored(j);
                if (level - 1 > k) {
                    mp.set(j);
                    BlockState s1 = state(mp);
                    int l = level - opacity(s1, mp);
                    if (l > k) {
                        if (from == null) from = LightEngine.QueueEntry.isFromEmptyShape(entry) ? Blocks.AIR.defaultBlockState() : state(mp.set(pos));
                        if (!occludes(pos, from, j, s1, d)) {
                            set(j, l);
                            if (l > 1) {
                                q.enqueue(j);
                                q.enqueue(LightEngine.QueueEntry.increaseSkipOneDirection(l, emptyShape(s1), d.getOpposite()));
                            }
                        }
                    }
                }
            }
        }

        void incSky(long pos, long entry, int level) {
            BlockState from = null;
            int empty = emptyBelowAtBorder(pos);
            for (Direction d : DIRS) {
                if (!LightEngine.QueueEntry.shouldPropagateInDirection(entry, d)) continue;
                long j = BlockPos.offset(pos, d);
                if (!storing(SectionPos.blockToSection(j))) continue;
                int k = stored(j);
                if (level - 1 > k) {
                    mp.set(j);
                    BlockState s1 = state(mp);
                    int i1 = level - opacity(s1, mp);
                    if (i1 > k) {
                        if (from == null) from = LightEngine.QueueEntry.isFromEmptyShape(entry) ? Blocks.AIR.defaultBlockState() : state(mp.set(pos));
                        if (!occludes(pos, from, j, s1, d)) {
                            set(j, i1);
                            if (i1 > 1) {
                                q.enqueue(j);
                                q.enqueue(LightEngine.QueueEntry.increaseSkipOneDirection(i1, emptyShape(s1), d.getOpposite()));
                            }
                            fromEmptySections(j, d, i1, empty);
                        }
                    }
                }
            }
        }

        boolean dataAtOrBelow(int sy) {
            return sy >= b.lowestY;
        }

        int emptyBelowAtBorder(long pos) {
            int y = BlockPos.getY(pos);
            if (SectionPos.sectionRelative(y) != 0) return 0;
            int x = BlockPos.getX(pos), z = BlockPos.getZ(pos);
            int rx = SectionPos.sectionRelative(x), rz = SectionPos.sectionRelative(z);
            if (rx != 0 && rx != 15 && rz != 0 && rz != 15) return 0;
            int sx = SectionPos.blockToSectionCoord(x), sy = SectionPos.blockToSectionCoord(y), sz = SectionPos.blockToSectionCoord(z);
            int n = 0;
            while (!storing(SectionPos.asLong(sx, sy - n - 1, sz)) && dataAtOrBelow(sy - n - 1)) n++;
            return n;
        }

        void fromEmptySections(long pos, Direction d, int level, int empty) {
            if (empty == 0) return;
            int x = BlockPos.getX(pos), z = BlockPos.getZ(pos);
            if (!crossed(d, SectionPos.sectionRelative(x), SectionPos.sectionRelative(z))) return;
            int y = BlockPos.getY(pos);
            int sx = SectionPos.blockToSectionCoord(x), sz = SectionPos.blockToSectionCoord(z);
            int j1 = SectionPos.blockToSectionCoord(y) - 1;
            int k1 = j1 - empty + 1;
            while (j1 >= k1) {
                if (!storing(SectionPos.asLong(sx, j1, sz))) {
                    j1--;
                } else {
                    int base = SectionPos.sectionToBlockCoord(j1);
                    for (int i2 = 15; i2 >= 0; i2--) {
                        long j2 = BlockPos.asLong(x, base + i2, z);
                        // vanilla writes this level whatever is there: lowering a brighter cell would make the result order dependent
                        if (stored(j2) > level) g.conflict = true;
                        g.emptyWrites.add(j2);
                        g.emptyWrites.add(level);
                        set(j2, level);
                        if (level > 1) {
                            q.enqueue(j2);
                            q.enqueue(LightEngine.QueueEntry.increaseSkipOneDirection(level, true, d.getOpposite()));
                        }
                    }
                    j1--;
                }
            }
        }

        static boolean crossed(Direction d, int x, int z) {
            return switch (d) {
                case NORTH -> z == 15;
                case SOUTH -> z == 0;
                case WEST -> x == 15;
                case EAST -> x == 0;
                default -> false;
            };
        }
    }
}
