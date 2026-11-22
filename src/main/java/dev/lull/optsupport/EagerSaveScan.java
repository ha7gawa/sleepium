package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * -Dlab.opt.EagerSaveScan (notes/88): ChunkMap.processUnloads' eager-save loop visits only the chunk holders whose
 * saveChunkIfNeeded can do something.
 *
 * <p>(1.20.1 port of lab-save135-1.21.1 9d369f33: the chunk is holder.getChunkToSave().getNow(null) as saveChunkIfNeeded reads it.)
 *
 * <p>Vanilla walks every visible chunk holder every tick (while the tick has time, until 20 chunks were saved) and calls
 * saveChunkIfNeeded on each. The modded factory (be_dense: 562 forceloaded chunks) has ~31.7 k visible holders, ~5.6 k
 * of them FULL: that walk was the whole 4-6 ms/tick of the chunk_unload section (the saves themselves ~0.1 ms a tick).
 *
 * <p>Two exact filters, both keeping the visible map's order (so the 20-a-tick cap and the time limit fall on the same
 * holders as vanilla's walk would reach first):
 * <ol>
 *   <li>Only holders with wasAccessibleSinceLastSave are listed: a holder without it returns false at its first check,
 *   with no effect. The list is rebuilt when the visible map is replaced (promoteChunkMap makes a new map object on
 *   every membership change) or when a holder that is not listed becomes accessible (ChunkHolder.updateFutures /
 *   refreshAccessibility: the only writes of the private flag). A listed holder that is no longer accessible is still
 *   visited (false, as in vanilla).</li>
 *   <li>A listed holder is QUIET after a visit that left it accessible, at a FULL ticket level, with a LevelChunk that is
 *   not unsaved and a POI column with no dirty section. Its next call could only return false with no effect (cooldown,
 *   readiness: early returns; else poi flush of a clean column, save of a clean chunk = false, refreshAccessibility at
 *   FULL = true again) until one of: its chunk becomes unsaved (read live from the chunk at every walk), its ticket level
 *   / futures / accessibility change (ChunkHolder.setTicketLevel, updateFutures, refreshAccessibility: hooks), a POI
 *   section of its column becomes dirty (SectionStorage.setDirty: hook; off the main thread every holder of that level
 *   loses QUIET). A quiet holder is skipped: one read of chunk.isUnsaved() instead of vanilla's walk through the holder's
 *   futures, its cooldown entry and the POI storage.</li>
 * </ol>
 *
 * <p>{@code -Dlab.eagerscan.verify=true}: every walk also checks every visible holder: one that is accessible but not
 * listed, and one that is skipped as quiet but whose vanilla call would not be a no-op (accessible at a non-FULL level,
 * an unsaved chunk, a dirty POI column, another chunk object) are counted and printed (must stay 0).
 */
public final class EagerSaveScan {
    private EagerSaveScan() {}

    public static final boolean VERIFY = Boolean.getBoolean("lab.eagerscan.verify");
    /** -Dlab.eagerscan.quiet=false: the first filter only */
    public static final boolean QUIET = !"false".equals(System.getProperty("lab.eagerscan.quiet"));

    /** bumped when a holder outside every list becomes accessible (main thread) */
    public static volatile long epoch;
    public static final AtomicLong rebuilds = new AtomicLong(), walks = new AtomicLong(), listed = new AtomicLong(), visible = new AtomicLong(),
            missing = new AtomicLong(), skipped = new AtomicLong(), visited = new AtomicLong(), wrongQuiet = new AtomicLong(), poiOffThread = new AtomicLong();

    /** ChunkHolder's place in its map's list */
    public interface Listed {
        boolean sleep$listed();

        void sleep$setListed(boolean listed, Index index, int idx);

        Index sleep$index();

        int sleep$idx();
    }

    /** a ChunkMap's list (ChunkMap implements this through its mixin) */
    public interface Owner {
        Index sleep$eagerIndex();
    }

    /** PoiManager's dirty set (SectionStorage accessor) */
    public interface DirtySections {
        LongSet sleep$dirty();
    }

    /** a ChunkMap's list of the holders its eager walk visits */
    public static final class Index {
        Long2ObjectLinkedOpenHashMap<ChunkHolder> builtFor;
        long builtEpoch = -1;
        ChunkHolder[] holders = new ChunkHolder[0];
        LevelChunk[] chunks = new LevelChunk[0];
        boolean[] quiet = new boolean[0];
        final Long2IntOpenHashMap posIndex = new Long2IntOpenHashMap();
        int size;
        /** the list entry the walk's iterator returned last */
        int cur = -1;
        /** chunk positions whose POI column got dirty off the main thread: every holder loses QUIET */
        volatile boolean poiAll;

        {
            posIndex.defaultReturnValue(-1);
        }

        /** SectionStorage.setDirty of a POI section of this level (main thread) */
        public void poiDirty(long chunkPos) {
            int i = posIndex.get(chunkPos);
            if (i >= 0 && i < size) quiet[i] = false;
        }

        /** a holder hook: its entry loses QUIET */
        void unquiet(int idx, ChunkHolder h) {
            if (idx >= 0 && idx < size && holders[idx] == h) quiet[idx] = false;
        }
    }

    /** ChunkHolder.updateFutures / refreshAccessibility / setTicketLevel (main thread) */
    public static void touched(ChunkHolder h, boolean accessible) {
        Listed l = (Listed) h;
        if (accessible && !l.sleep$listed()) epoch++;
        Index ix = l.sleep$index();
        if (ix != null) ix.unquiet(l.sleep$idx(), h);
    }

    /** SectionStorage.setDirty of a PoiManager */
    public static void poiDirty(Object level, long sectionPos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        Index ix = ((Owner) sl.getChunkSource().chunkMap).sleep$eagerIndex();
        if (sl.getServer().isSameThread()) ix.poiDirty(ChunkPos.asLong(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
        else {
            ix.poiAll = true;
            poiOffThread.incrementAndGet();
        }
    }

    /** the walk's iterator (vanilla: visibleChunkMap.values().iterator()) */
    public static ObjectIterator<ChunkHolder> iterator(Index ix, Long2ObjectLinkedOpenHashMap<ChunkHolder> map, Object poi, int minSection, int maxSection) {
        long e = epoch;
        if (ix.builtFor != map || ix.builtEpoch != e) {
            for (int i = 0; i < ix.size; i++) ((Listed) ix.holders[i]).sleep$setListed(false, null, -1);
            int cap = Math.max(16, ix.size + (ix.size >> 2));
            ChunkHolder[] a = new ChunkHolder[cap];
            int n = 0;
            ix.posIndex.clear();
            for (ChunkHolder h : map.values()) {
                if (h.wasAccessibleSinceLastSave()) {
                    if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
                    ((Listed) h).sleep$setListed(true, ix, n);
                    ix.posIndex.put(h.getPos().toLong(), n);
                    a[n++] = h;
                } else ((Listed) h).sleep$setListed(false, null, -1);
            }
            ix.holders = a;
            ix.chunks = new LevelChunk[a.length];
            ix.quiet = new boolean[a.length];
            ix.size = n;
            ix.builtFor = map;
            ix.builtEpoch = e;
            rebuilds.incrementAndGet();
        }
        if (ix.poiAll) {
            ix.poiAll = false;
            java.util.Arrays.fill(ix.quiet, false);
        }
        walks.incrementAndGet();
        if (VERIFY) verify(ix, map, poi, minSection, maxSection);
        final ChunkHolder[] a = ix.holders;
        final LevelChunk[] c = ix.chunks;
        final boolean[] q = ix.quiet;
        final int n = ix.size;
        ix.cur = -1;
        return new ObjectIterator<>() {
            int i;

            @Override
            public boolean hasNext() {
                while (i < n) {
                    if (!q[i]) return true;
                    if (c[i].isUnsaved()) {
                        q[i] = false;
                        return true;
                    }
                    skipped.incrementAndGet();
                    i++;
                }
                return false;
            }

            @Override
            public ChunkHolder next() {
                if (!hasNext()) throw new NoSuchElementException();
                visited.incrementAndGet();
                ix.cur = i;
                return a[i++];
            }
        };
    }

    /** after the walk's saveChunkIfNeeded of the entry it returned last: QUIET if that call left nothing to do */
    public static void visitedOne(Index ix, ChunkHolder h, Object poi, int minSection, int maxSection) {
        if (!QUIET) return;
        int i = ix.cur;
        if (i < 0 || i >= ix.size || ix.holders[i] != h) return;
        LevelChunk lc = noOp(h, poi, minSection, maxSection);
        ix.chunks[i] = lc;
        ix.quiet[i] = lc != null;
    }

    /** the holder's LevelChunk when a saveChunkIfNeeded of it now would change nothing, else null */
    private static LevelChunk noOp(ChunkHolder h, Object poi, int minSection, int maxSection) {
        if (!h.wasAccessibleSinceLastSave() || !ChunkLevel.fullStatus(h.getTicketLevel()).isOrAfter(FullChunkStatus.FULL)) return null;
        ChunkAccess c = h.getChunkToSave().getNow(null);   // (1.20.1: saveChunkIfNeeded's chunk; 1.21.1 getLatestChunk)
        if (!(c instanceof LevelChunk lc) || lc.isUnsaved()) return null;
        LongSet dirty = ((DirtySections) poi).sleep$dirty();
        if (!dirty.isEmpty()) {
            ChunkPos p = h.getPos();
            for (int y = minSection; y < maxSection; y++) if (dirty.contains(SectionPos.asLong(p.x, y, p.z))) return null;
        }
        return lc;
    }

    private static void verify(Index ix, Long2ObjectLinkedOpenHashMap<ChunkHolder> map, Object poi, int minSection, int maxSection) {
        int miss = 0, wrong = 0;
        for (ChunkHolder h : map.values()) if (h.wasAccessibleSinceLastSave() && !((Listed) h).sleep$listed()) miss++;
        for (int i = 0; i < ix.size; i++)
            if (ix.quiet[i] && !ix.chunks[i].isUnsaved() && noOp(ix.holders[i], poi, minSection, maxSection) != ix.chunks[i]) wrong++;
        visible.addAndGet(map.size());
        listed.addAndGet(ix.size);
        if (miss > 0 && missing.getAndAdd(miss) < 10)
            System.out.println("[sleep/opt] eager save scan VERIFY: " + miss + " accessible holders not in the list (of " + map.size() + ")");
        if (wrong > 0 && wrongQuiet.getAndAdd(wrong) < 10)
            System.out.println("[sleep/opt] eager save scan VERIFY: " + wrong + " holders skipped as quiet whose call would not be a no-op");
    }

    public static String report() {
        return "eager save scan: walks " + walks + ", rebuilds " + rebuilds + ", visited " + visited + ", skipped quiet " + skipped + ", POI dirtied off-thread " + poiOffThread
                + (VERIFY ? ", avg listed " + listed.get() / Math.max(1, walks.get()) + " of " + visible.get() / Math.max(1, walks.get())
                + " visible, accessible but not listed " + missing + ", wrongly quiet " + wrongQuiet : "");
    }
}
