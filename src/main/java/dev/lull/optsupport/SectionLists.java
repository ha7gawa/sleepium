package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

/**
 * exp/section-lists (client): the frame's passes over every visible section (three opaque layers, translucent,
 * tripwire, block entities; each a pointer chase through thousands of sections) replaced by compact lists built in one
 * pass, in the same order, and rebuilt only when the visible set or a section's contents changed.
 *
 * <p>The lists hold exactly the sections each vanilla loop would not skip: a layer's sections with an arena entry
 * (ChunkMultiDraw), the translucent / tripwire sections whose compiled layer is not empty, the sections with renderable
 * block entities. A loop over a list therefore does what the loop over all visible sections did. Changes: the visible
 * list compared by references with the last one (no section is read), and a generation bumped by a compiled chunk,
 * RenderChunk.reset and arena stores / releases.
 *
 * <p>The rebuild reads no section either: what decides the lists is kept a section, by its index (RenderChunk.index),
 * in one record of a dense array - a summary (the layers with an entry, translucent, tripwire, block entities;
 * recomputed only for a section that changed), whether it may be dirty (set by creation / setDirty / reset, cleared
 * when seen clean), its place in the visible list and the dirty list's build. Every turn of the camera makes a new
 * visible list (applyFrustum each 2 degrees); reading each section's info, chunk, entries, compiled layers and block
 * entities there cost milliseconds at far view distances. The indices come with the list from the flat frustum walk
 * (SectionSet), else from the chunk. Changes made on another thread queue and apply on the render thread.
 *
 * <p>1.20.1: the visible list (LevelRenderer.renderChunksInFrustum) holds RenderChunkInfo, not the chunks: the lists
 * hold those same infos (the loops read them), the per-chunk state is on the RenderChunk (ChunkMultiDraw.chunkOf).
 */
public final class SectionLists {
    private static int gen, builtGen = -1;
    private static Object[] lastRefs = new Object[0];
    private static int lastSize = -1;
    private static ObjectArrayList<?> built;
    @SuppressWarnings("unchecked")
    private static final ObjectArrayList<Object>[] LAYERS = new ObjectArrayList[8];
    private static final ObjectArrayList<Object> TRANSLUCENT = new ObjectArrayList<>(), TRIPWIRE = new ObjectArrayList<>(),
            BLOCK_ENTITIES = new ObjectArrayList<>(), DIRTY_OUT = new ObjectArrayList<>();
    /** the dirty chunks themselves (setDirty sees the chunk, not its info) */
    private static final ObjectArrayList<ChunkRenderDispatcher.RenderChunk> DIRTY = new ObjectArrayList<>(), DIRTY_SORT = new ObjectArrayList<>();
    /** id of the current build (the visible indices and the dirty list belong to it) */
    private static int build;
    public static long builds, reuses, summaries, buildNs, dirtySum, changes, offChanges;
    /** the layer renderSectionLayer is drawing (set at its head) */
    public static RenderType currentLayer;

    private static final int TRANSLUCENT_BIT = 1 << 8, TRIPWIRE_BIT = 1 << 9, BE_BIT = 1 << 10, SUM_MASK = (1 << 11) - 1;
    private static final int STALE = 1 << 29, MAYBE_DIRTY = 1 << 30;
    /**
     * a record of 4 ints by chunk index: flags (the summary in SUM_MASK, STALE: to compute, MAYBE_DIRTY), the place in
     * the visible list of build [2], the build whose dirty list holds the chunk
     */
    private static int[] rec = new int[0];

    /** the flat frustum walk's output (SectionSet): the infos it added to the list from walkFrom on, and their chunks' indices */
    public static Object[] walkRefs = new Object[0];
    public static int[] walkIdx = new int[0];
    public static int walkFrom, walkN;

    /** changes from other threads: applied on the render thread before the next rebuild */
    private static final Object OFF = new Object();
    private static final ArrayList<ChunkRenderDispatcher.RenderChunk> OFF_CHANGED = new ArrayList<>(), OFF_DIRTIED = new ArrayList<>();
    private static volatile boolean offPending;

    static {
        for (int i = 0; i < LAYERS.length; i++) LAYERS[i] = new ObjectArrayList<>();
    }

    private SectionLists() {}

    private static void ensure(int idx) {
        int cap = rec.length >> 2;
        if (idx < cap) return;
        int c = Math.max(idx + 1, Math.max(1024, cap * 2));
        rec = java.util.Arrays.copyOf(rec, c << 2);
        for (int i = cap; i < c; i++) {
            rec[i << 2] = STALE | MAYBE_DIRTY;
            rec[(i << 2) + 2] = -1;
            rec[(i << 2) + 3] = -1;
        }
    }

    /** The flat walk is about to add up to n infos to the list, from index from on. */
    public static void walkBegin(int from, int n) {
        walkFrom = from;
        walkN = 0;
        if (walkRefs.length < n) {
            walkRefs = new Object[Math.max(n, walkRefs.length * 2)];
            walkIdx = new int[walkRefs.length];
        }
    }

    /** A chunk was made (RenderChunk.<init>): new, uncompiled, dirty. */
    public static void born(ChunkRenderDispatcher.RenderChunk s) {
        changed(s);
        dirtied(s);
    }

    /** The chunk's list membership may have changed (compiled, reset, an arena entry stored / released). */
    public static void changed(ChunkRenderDispatcher.RenderChunk s) {
        if (!RenderSystem.isOnRenderThread()) {
            synchronized (OFF) {
                offChanges++;
                OFF_CHANGED.add(s);
                offPending = true;
            }
            return;
        }
        int idx = s.index;
        changes++;
        if (idx >= 0) {
            ensure(idx);
            rec[idx << 2] |= STALE;
        }
        gen++;
    }

    private static void drainOff() {
        if (!offPending) return;
        synchronized (OFF) {
            for (var s : OFF_CHANGED) changed(s);
            for (var s : OFF_DIRTIED) dirtied(s);
            OFF_CHANGED.clear();
            OFF_DIRTIED.clear();
            offPending = false;
        }
    }

    /** The build and content generation the current lists come from (after a refresh): equal = the same lists. */
    public static long version() {
        return ((long) build << 32) | (builtGen & 0xFFFFFFFFL);
    }

    private static int summarize(ChunkRenderDispatcher.RenderChunk s) {
        summaries++;
        int b = 0;
        Object[] md = ((ChunkMultiDraw.ChunkEntries) s).sleep$md();
        for (int k = 0; k < LAYERS.length; k++) if (md[2 * k] != null || md[2 * k + 1] != null) b |= 1 << k;
        var c = s.getCompiledChunk();
        if (!c.isEmpty(RenderType.translucent())) b |= TRANSLUCENT_BIT;
        if (!c.isEmpty(RenderType.tripwire())) b |= TRIPWIRE_BIT;
        if (!c.getRenderableBlockEntities().isEmpty()) b |= BE_BIT;
        return b;
    }

    private static void refresh(ObjectArrayList<?> visible) {
        drainOff();
        int n = visible.size();
        Object[] refs = visible.elements();
        if (builtGen == gen && built == visible && lastSize == n && java.util.Arrays.equals(refs, 0, n, lastRefs, 0, n)) {
            reuses++;
            return;
        }
        long t0 = System.nanoTime();
        for (var l : LAYERS) l.clear();
        TRANSLUCENT.clear();
        TRIPWIRE.clear();
        BLOCK_ENTITIES.clear();
        DIRTY.clear();
        build++;
        Object[] wr = walkRefs;
        int[] wi = walkIdx;
        int wf = walkFrom, wn = walkN;
        for (int i = 0; i < n; i++) {
            Object info = refs[i];
            int j = i - wf, idx;
            if (j >= 0 && j < wn && wr[j] == info) idx = wi[j];
            else idx = ChunkMultiDraw.chunkOf(info).index;
            int b;
            if (idx < 0) {   // not the level's chunk (Sable's ship sections): the chunk's own fields
                var s = ChunkMultiDraw.chunkOf(info);
                ChunkMultiDraw.ChunkEntries ce = (ChunkMultiDraw.ChunkEntries) s;
                ce.sleep$setVis(i, build);
                if (s.isDirty()) {
                    DIRTY.add(s);
                    ce.sleep$setDirtyBuild(build);
                }
                b = summarize(s);
            } else {
                if (idx >= rec.length >> 2) ensure(idx);
                int r = idx << 2, f = rec[r];
                rec[r + 1] = i;
                rec[r + 2] = build;
                if ((f & MAYBE_DIRTY) != 0) {
                    var s = ChunkMultiDraw.chunkOf(info);
                    if (s.isDirty()) {
                        DIRTY.add(s);
                        rec[r + 3] = build;
                    } else f &= ~MAYBE_DIRTY;
                }
                if ((f & STALE) != 0) f = (f & MAYBE_DIRTY) | summarize(ChunkMultiDraw.chunkOf(info));
                rec[r] = f;
                b = f & SUM_MASK;
            }
            if (b == 0) continue;
            for (int k = 0; k < LAYERS.length; k++) if ((b & 1 << k) != 0) LAYERS[k].add(info);
            if ((b & TRANSLUCENT_BIT) != 0) TRANSLUCENT.add(info);
            if ((b & TRIPWIRE_BIT) != 0) TRIPWIRE.add(info);
            if ((b & BE_BIT) != 0) BLOCK_ENTITIES.add(info);
        }
        if (lastRefs.length < n) lastRefs = new Object[Math.max(n, lastRefs.length * 2)];
        System.arraycopy(refs, 0, lastRefs, 0, n);
        lastSize = n;
        built = visible;
        builtGen = gen;
        builds++;
        buildNs += System.nanoTime() - t0;
        dirtySum += DIRTY.size();
    }

    /** ChunkMultiDraw: the visible sections with an arena entry for layer index li. */
    public static ObjectArrayList<Object> layer(ObjectArrayList<?> visible, int li) {
        refresh(visible);
        return LAYERS[li];
    }

    /** renderChunkLayer's own loops: for translucent and tripwire, only the sections that have the layer. */
    @SuppressWarnings("rawtypes")
    public static ObjectArrayList forLayer(ObjectArrayList visible) {
        RenderType t = currentLayer;
        if (t == RenderType.translucent()) {
            refresh(visible);
            return TRANSLUCENT;
        }
        if (t == RenderType.tripwire()) {
            refresh(visible);
            return TRIPWIRE;
        }
        return visible;
    }

    /** ChunkMultiDraw.drawTranslucent: the visible sections whose translucent layer is not empty, in the visible order */
    @SuppressWarnings("rawtypes")
    public static ObjectArrayList translucent(ObjectArrayList visible) {
        refresh(visible);
        return TRANSLUCENT;
    }

    /** renderLevel's block entity loop: only the sections that have renderable block entities. */
    @SuppressWarnings("rawtypes")
    public static ObjectArrayList blockEntities(ObjectArrayList visible) {
        refresh(visible);
        return BLOCK_ENTITIES;
    }

    /** RenderChunk.setDirty / reset / creation (after the field is set): a visible chunk of the current build joins its dirty list (once). */
    public static void dirtied(ChunkRenderDispatcher.RenderChunk s) {
        if (!RenderSystem.isOnRenderThread()) {
            synchronized (OFF) {
                OFF_DIRTIED.add(s);
                offPending = true;
            }
            return;
        }
        int idx = s.index;
        if (idx < 0) {
            ChunkMultiDraw.ChunkEntries ce = (ChunkMultiDraw.ChunkEntries) s;
            if (ce.sleep$visIdx(build) >= 0 && ce.sleep$dirtyBuild() != build) {
                DIRTY.add(s);
                ce.sleep$setDirtyBuild(build);
            }
            return;
        }
        ensure(idx);
        int r = idx << 2, p = rec[r + 1];
        rec[r] |= MAYBE_DIRTY;
        // visible in the current build: its info at its place (an index can belong to a released chunk too)
        if (rec[r + 2] == build && rec[r + 3] != build && p >= 0 && p < lastSize && ChunkMultiDraw.chunkOf(lastRefs[p]) == s) {
            DIRTY.add(s);
            rec[r + 3] = build;
        }
    }

    private static int visIdx(ChunkRenderDispatcher.RenderChunk s, int b) {
        int idx = s.index;
        return idx < 0 ? ((ChunkMultiDraw.ChunkEntries) s).sleep$visIdx(b) : rec[(idx << 2) + 1];
    }

    /**
     * compileSections' loop: the visible sections that are dirty, in visible-list order (the loop tests isDirty itself;
     * the ones no longer dirty are dropped here). Sections that are dirty but not visible are never walked. 1.20.1: the
     * loop reads infos - each dirty chunk's own, found by its index in the visible list of this build.
     */
    @SuppressWarnings("rawtypes")
    public static ObjectArrayList dirtyVisible(ObjectArrayList visible) {
        refresh(visible);
        DIRTY_OUT.clear();
        int w = 0;
        for (int i = 0; i < DIRTY.size(); i++) {
            var s = DIRTY.get(i);
            if (!s.isDirty()) {
                int idx = s.index;
                if (idx < 0) ((ChunkMultiDraw.ChunkEntries) s).sleep$setDirtyBuild(-1);
                else {
                    rec[(idx << 2) + 3] = -1;
                    rec[idx << 2] &= ~MAYBE_DIRTY;
                }
                continue;
            }
            DIRTY.set(w++, s);
            DIRTY_SORT.add(s);
        }
        DIRTY.size(w);
        int b = build;
        if (DIRTY_SORT.size() > 1) DIRTY_SORT.sort((a, c) -> Integer.compare(visIdx(a, b), visIdx(c, b)));
        for (int i = 0; i < DIRTY_SORT.size(); i++) DIRTY_OUT.add(lastRefs[visIdx(DIRTY_SORT.get(i), b)]);
        DIRTY_SORT.clear();
        return DIRTY_OUT;
    }

    public static String report() {
        return "section lists: built " + builds + ", reused " + reuses + ", summaries " + summaries + String.format(java.util.Locale.ROOT,
                " (a build %.3f ms, %.1f dirty), changes %d (off the render thread %d)", buildNs / 1e6 / Math.max(1, builds), dirtySum / (double) Math.max(1, builds), changes, offChanges);
    }
}
