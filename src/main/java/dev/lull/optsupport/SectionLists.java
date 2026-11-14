package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * exp/section-lists (client): the frame's passes over every visible section (three opaque layers, translucent,
 * tripwire, block entities; each a pointer chase through thousands of sections) replaced by compact lists built in one
 * pass, in the same order, and rebuilt only when the visible set or a section's contents changed.
 *
 * <p>The lists hold exactly the sections each vanilla loop would not skip: a layer's sections with an arena entry
 * (ChunkMultiDraw), the translucent / tripwire sections whose compiled layer is not empty, the sections with renderable
 * block entities. A loop over a list therefore does what the loop over all visible sections did. Changes: the visible
 * list compared by references with the last one (no section is read), and a generation bumped by setCompiled,
 * RenderSection.reset and arena stores / releases.
 *
 * <p>The rebuild reads no section either: what decides the lists is kept a section, by its index (RenderSection.index),
 * in one record of a dense array - a summary (the layers with an entry, translucent, tripwire, block entities;
 * recomputed only for a section that changed), whether it may be dirty (set by creation / setDirty / reset, cleared
 * when seen clean), its place in the visible list and the dirty list's build. Every turn of the camera makes a new
 * visible list (applyFrustum each 2 degrees); reading each section's entries, compiled layers and block entities there
 * cost ~1.7 ms at view distance 32. The indices come with the list from the flat frustum walk (SectionSet), else from
 * the section. Changes made on another thread (setCompiled(EMPTY) runs on a compile worker) queue and apply on the
 * render thread before the next rebuild.
 */
public final class SectionLists {
    private static int gen, builtGen = -1;
    private static Object[] lastRefs = new Object[0];
    private static int lastSize = -1;
    private static ObjectArrayList<SectionRenderDispatcher.RenderSection> built;
    @SuppressWarnings("unchecked")
    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection>[] LAYERS = new ObjectArrayList[8];
    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> TRANSLUCENT = new ObjectArrayList<>(), TRIPWIRE = new ObjectArrayList<>(),
            BLOCK_ENTITIES = new ObjectArrayList<>(), DIRTY = new ObjectArrayList<>(), DIRTY_OUT = new ObjectArrayList<>();
    /** id of the current build (the visible indices and the dirty list belong to it) */
    private static int build;
    public static long builds, reuses, summaries, buildNs, dirtySum, changes, offChanges;
    /** the layer renderSectionLayer is drawing (set at its head) */
    public static RenderType currentLayer;

    private static final int TRANSLUCENT_BIT = 1 << 8, TRIPWIRE_BIT = 1 << 9, BE_BIT = 1 << 10, SUM_MASK = (1 << 11) - 1;
    private static final int STALE = 1 << 29, MAYBE_DIRTY = 1 << 30;
    /**
     * a record of 4 ints by section index: flags (the summary in SUM_MASK, STALE: to compute, MAYBE_DIRTY), the place in
     * the visible list of build [2], the build whose dirty list holds the section
     */
    private static int[] rec = new int[0];

    /** the flat frustum walk's output (SectionSet): the sections it added to the list from walkFrom on, and their indices */
    public static Object[] walkRefs = new Object[0];
    public static int[] walkIdx = new int[0];
    public static int walkFrom, walkN;

    /** changes from other threads: applied on the render thread before the next rebuild */
    private static final Object OFF = new Object();
    private static final ArrayList<SectionRenderDispatcher.RenderSection> OFF_CHANGED = new ArrayList<>(), OFF_DIRTIED = new ArrayList<>();
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

    /** The flat walk is about to add up to n sections to the list, from index from on. */
    public static void walkBegin(int from, int n) {
        walkFrom = from;
        walkN = 0;
        if (walkRefs.length < n) {
            walkRefs = new Object[Math.max(n, walkRefs.length * 2)];
            walkIdx = new int[walkRefs.length];
        }
    }

    /** A section was made (RenderSection.<init>): new, uncompiled, dirty. */
    public static void born(SectionRenderDispatcher.RenderSection s) {
        changed(s);
        dirtied(s);
    }

    /** The section's list membership may have changed (compiled, reset, an arena entry stored / released). */
    public static void changed(SectionRenderDispatcher.RenderSection s) {
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

    private static int summarize(SectionRenderDispatcher.RenderSection s) {
        summaries++;
        int b = 0;
        Object[] md = ((ChunkMultiDraw.ChunkEntries) s).sleep$md();
        for (int k = 0; k < LAYERS.length; k++) if (md[2 * k] != null || md[2 * k + 1] != null) b |= 1 << k;
        var c = s.getCompiled();
        if (!c.isEmpty(RenderType.translucent())) b |= TRANSLUCENT_BIT;
        if (!c.isEmpty(RenderType.tripwire())) b |= TRIPWIRE_BIT;
        if (!c.getRenderableBlockEntities().isEmpty()) b |= BE_BIT;
        return b;
    }

    /** a raw add: the element stays an Object, so no cast reads its header (a cache miss a section) */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void put(ObjectArrayList list, Object o) {
        list.add(o);
    }

    private static void refresh(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
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
            Object o = refs[i];
            int j = i - wf, idx;
            if (j >= 0 && j < wn && wr[j] == o) idx = wi[j];
            else idx = ((SectionRenderDispatcher.RenderSection) o).index;
            int b;
            if (idx < 0) {   // not the level's section (Sable's ship sections): the section's own fields
                var s = (SectionRenderDispatcher.RenderSection) o;
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
                    var s = (SectionRenderDispatcher.RenderSection) o;
                    if (s.isDirty()) {
                        DIRTY.add(s);
                        rec[r + 3] = build;
                    } else f &= ~MAYBE_DIRTY;
                }
                if ((f & STALE) != 0) f = (f & MAYBE_DIRTY) | summarize((SectionRenderDispatcher.RenderSection) o);
                rec[r] = f;
                b = f & SUM_MASK;
            }
            if (b == 0) continue;
            for (int k = 0; k < LAYERS.length; k++) if ((b & 1 << k) != 0) put(LAYERS[k], o);
            if ((b & TRANSLUCENT_BIT) != 0) put(TRANSLUCENT, o);
            if ((b & TRIPWIRE_BIT) != 0) put(TRIPWIRE, o);
            if ((b & BE_BIT) != 0) put(BLOCK_ENTITIES, o);
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
    public static ObjectArrayList<SectionRenderDispatcher.RenderSection> layer(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, int li) {
        refresh(visible);
        return LAYERS[li];
    }

    /** renderSectionLayer's own loops: for translucent and tripwire, only the sections that have the layer. */
    public static ObjectArrayList<SectionRenderDispatcher.RenderSection> forLayer(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
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
    public static ObjectArrayList<SectionRenderDispatcher.RenderSection> translucent(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
        refresh(visible);
        return TRANSLUCENT;
    }

    /** renderLevel's block entity loop: only the sections that have renderable block entities. */
    public static ObjectArrayList<SectionRenderDispatcher.RenderSection> blockEntities(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
        refresh(visible);
        return BLOCK_ENTITIES;
    }

    /** RenderSection.setDirty / reset / creation (after the field is set): a visible section of the current build joins its dirty list (once). */
    public static void dirtied(SectionRenderDispatcher.RenderSection s) {
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
        // visible in the current build: the same object at its place (an index can belong to a released section too)
        if (rec[r + 2] == build && rec[r + 3] != build && p >= 0 && p < lastSize && lastRefs[p] == s) {
            DIRTY.add(s);
            rec[r + 3] = build;
        }
    }

    private static int visIdx(SectionRenderDispatcher.RenderSection s, int b) {
        int idx = s.index;
        return idx < 0 ? ((ChunkMultiDraw.ChunkEntries) s).sleep$visIdx(b) : rec[(idx << 2) + 1];
    }

    /**
     * compileSections' loop: the visible sections that are dirty, in visible-list order (the loop tests isDirty itself;
     * the ones no longer dirty are dropped here). Sections that are dirty but not visible are never walked.
     */
    public static ObjectArrayList<SectionRenderDispatcher.RenderSection> dirtyVisible(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
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
            DIRTY_OUT.add(s);
        }
        DIRTY.size(w);
        if (DIRTY_OUT.size() > 1) {
            int b = build;
            DIRTY_OUT.sort((a, c) -> Integer.compare(visIdx(a, b), visIdx(c, b)));
        }
        return DIRTY_OUT;
    }

    public static String report() {
        return "section lists: built " + builds + ", reused " + reuses + ", summaries " + summaries + String.format(java.util.Locale.ROOT,
                " (a build %.3f ms, %.1f dirty), changes %d (off the render thread %d)", buildNs / 1e6 / Math.max(1, builds), dirtySum / (double) Math.max(1, builds), changes, offChanges);
    }
}
