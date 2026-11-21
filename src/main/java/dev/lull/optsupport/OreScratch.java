package dev.lull.optsupport;

import java.util.BitSet;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * WorldgenFeatureAlloc (ores): OreFeature.doPlace's temporaries, one set per thread, reused.
 *
 * <p>Per call vanilla makes a BitSet (and its long[]), a double[size * 4], a MutableBlockPos, a BulkSectionAccess (a
 * Long2ObjectOpenHashMap with its key and value arrays) and, for every candidate block and target, a bound method
 * reference {@code bulksectionaccess::getBlockState} for canPlaceOre (~4 % of the workers' allocation). Here: the
 * bit set cleared, the array grown when too small (every slot read is written first in the same call), and this
 * object stands for the BulkSectionAccess and its method reference - the same getSection logic (the same
 * level.getSectionIndex / getSectionsCount / getChunk / getSection calls, a section acquired the first time it is
 * seen, the last section remembered), the sections released when the call ends (as BulkSectionAccess.close does,
 * also when it throws). The acquired sections are a short list instead of a hash map: an ore touches a few sections.
 *
 * <p>doPlace calls nothing that places another ore (block writes go straight to the section), so the thread's set is
 * never used twice at once; a nested call (a mod's subclass) gets a fresh set anyway.
 */
public final class OreScratch implements Function<BlockPos, BlockState> {
    private static final ThreadLocal<OreScratch> TL = ThreadLocal.withInitial(OreScratch::new);

    private boolean busy;
    public final BitSet bits = new BitSet(512);
    public final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private double[] values = new double[256];
    private LevelAccessor level;
    private long[] keys = new long[8];
    private LevelChunkSection[] sections = new LevelChunkSection[8];
    private int count;
    private LevelChunkSection lastSection;
    private long lastSectionKey;

    private OreScratch() {}

    public static OreScratch take(LevelAccessor level) {
        OreScratch s = TL.get();
        if (s.busy) s = new OreScratch();
        s.busy = true;
        s.level = level;
        s.bits.clear();
        s.lastSection = null;
        s.lastSectionKey = 0L;
        return s;
    }

    /** a double[] of at least n slots (contents undefined) */
    public double[] values(int n) {
        if (values.length < n) values = new double[Math.max(n, values.length * 2)];
        return values;
    }

    /** BulkSectionAccess.getSection */
    public LevelChunkSection getSection(BlockPos pos) {
        int i = this.level.getSectionIndex(pos.getY());
        if (i >= 0 && i < this.level.getSectionsCount()) {
            long j = SectionPos.asLong(pos);
            if (this.lastSection == null || this.lastSectionKey != j) {
                this.lastSection = acquired(j, i, pos);
                this.lastSectionKey = j;
            }
            return this.lastSection;
        }
        return null;
    }

    /** the computeIfAbsent of BulkSectionAccess.getSection */
    private LevelChunkSection acquired(long key, int index, BlockPos pos) {
        for (int k = 0; k < count; k++)
            if (keys[k] == key) return sections[k];
        ChunkAccess chunk = this.level.getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        LevelChunkSection section = chunk.getSection(index);
        section.acquire();
        if (count == keys.length) {
            keys = java.util.Arrays.copyOf(keys, count * 2);
            sections = java.util.Arrays.copyOf(sections, count * 2);
        }
        keys[count] = key;
        sections[count++] = section;
        return section;
    }

    /** BulkSectionAccess.getBlockState */
    @Override
    public BlockState apply(BlockPos pos) {
        LevelChunkSection section = this.getSection(pos);
        if (section == null) return Blocks.AIR.defaultBlockState();
        return section.getBlockState(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()));
    }

    /** BulkSectionAccess.close, and the set given back */
    public void close() {
        try {
            for (int k = 0; k < count; k++) sections[k].release();
        } finally {
            java.util.Arrays.fill(sections, 0, count, null);
            count = 0;
            lastSection = null;
            level = null;
            busy = false;
        }
    }
}
