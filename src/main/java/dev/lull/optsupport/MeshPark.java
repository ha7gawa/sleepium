package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * -Dlab.meshPark (11-18, with lab.meshMemo): a section's mesh outlives its ViewArea slot.
 *
 * <p>Flying at distance 32 the server forgets the columns behind and sends them again when the camera turns back; by
 * then ViewArea has re-placed their slots (setOrigin -> reset) and the meshes are gone - 30 % of all meshing in the
 * zoo orbit rebuilt sections from exactly the inputs of a mesh thrown away moments before (notes/79). Here a reset
 * section's arena entries are not released but parked under its world position with its compiled state and its
 * inputs' hash (MeshMemo); a compile task for that position whose inputs hash the same takes them instead of meshing
 * (no tesselation, no upload) - on the render thread they are put back on the section and set as its compile, as
 * vanilla's own compile would. Not parked: sections without a memo (block entities, mods' extra geometry), with a
 * translucent layer (sorted for the camera of its compile), or not wholly in the arenas. Shader light (LightVolumes): the
 * recipes' info is kept and the section's volume refilled from the current light when put back, as for a new mesh.
 * The parked vertices are bounded (-Dlab.meshPark.mb, 64 by default): the oldest are released first (a FIFO ring).
 *
 * <p>-Dlab.meshMemo.verify=true: a take does not skip the compile - the new mesh is compared with the parked one's
 * checksums (MeshMemo.committed), and the parked entries are released.
 */
public final class MeshPark {
    private MeshPark() {}

    public static final boolean ON = Boolean.getBoolean("lab.meshPark") && MeshMemo.ON;
    private static final long BUDGET_VERTICES = Long.getLong("lab.meshPark.mb", 64) * 1024 * 1024 / 32;

    static final class Parked {
        long origin, hash;
        SectionRenderDispatcher.CompiledSection compiled;
        Object[] md;
        long[] crcs;
        LightVolumes.CompileInfo light;
        int vertices;
    }

    private static final Object LOCK = new Object();
    private static final Long2ObjectOpenHashMap<Parked> BY_POS = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<Parked> RING = new ArrayDeque<>();
    private static long parkedVertices;
    /** taken on a worker, to be put back on the render thread: {section, parked, section epoch at the take} */
    private static final ConcurrentLinkedQueue<Object[]> ADOPT = new ConcurrentLinkedQueue<>();
    /** parked entries to release on the render thread (a stale take, a verify take) */
    private static final ConcurrentLinkedQueue<Parked> RELEASE = new ConcurrentLinkedQueue<>();

    public static final AtomicLong PARKED = new AtomicLong(), REFUSED = new AtomicLong(), TAKEN = new AtomicLong(), STALE = new AtomicLong(),
            ADOPTED = new AtomicLong(), DROPPED = new AtomicLong(), EVICTED = new AtomicLong(), RESETS = new AtomicLong(), NEVER_MESHED = new AtomicLong(),
            EMPTY = new AtomicLong(), NO_MEMO = new AtomicLong();

    /** RenderSection.reset (render thread), before its entries are released: park them instead when it can be kept */
    static void park(SectionRenderDispatcher.RenderSection s) {
        if (!ON || s.index < 0 || RegionDraw.on() || !(s instanceof MeshMemo.Holder hd)) return;
        long memo = hd.sleep$memo();
        SectionRenderDispatcher.CompiledSection c = s.getCompiled();
        RESETS.incrementAndGet();
        if (memo == 0) {
            if (c == SectionRenderDispatcher.CompiledSection.UNCOMPILED) NEVER_MESHED.incrementAndGet();
            else if (c.hasNoRenderableLayers()) EMPTY.incrementAndGet();
            else NO_MEMO.incrementAndGet();
        }
        if (memo == 0 || c == SectionRenderDispatcher.CompiledSection.UNCOMPILED || c.hasNoRenderableLayers() || !c.isEmpty(RenderType.translucent())
                || !c.getRenderableBlockEntities().isEmpty()) {
            if (memo != 0) REFUSED.incrementAndGet();
            return;
        }
        Object[] md = ((ChunkMultiDraw.ChunkEntries) s).sleep$md();
        int vertices = 0;
        for (int li = 0; li < ChunkMultiDraw.LAYERS.size(); li++) {
            RenderType t = ChunkMultiDraw.LAYERS.get(li);
            boolean has = md[2 * li] != null || md[2 * li + 1] != null;
            if (!c.isEmpty(t) && !has) {   // a layer drawn the vanilla way: not wholly ours
                REFUSED.incrementAndGet();
                return;
            }
        }
        Parked p = new Parked();
        p.origin = s.getOrigin().asLong();
        p.hash = memo;
        p.compiled = c;
        p.md = md.clone();
        for (Object o : p.md) if (o instanceof ChunkMultiDraw.Entry e) vertices += e.vertices;
        p.vertices = vertices;
        if (MeshMemo.VERIFY) p.crcs = hd.sleep$crcs().clone();
        if (LightVolumes.ON) p.light = LightVolumes.parkInfo(s);   // (shader light: the recipes' info, the volume refilled when put back)
        java.util.Arrays.fill(md, null);   // (detached: the release after this finds nothing)
        SectionLists.changed(s);
        synchronized (LOCK) {
            Parked old = BY_POS.put(p.origin, p);
            if (old != null) {
                parkedVertices -= old.vertices;
                release(old);
            }
            RING.add(p);
            parkedVertices += vertices;
            while (parkedVertices > BUDGET_VERTICES && !RING.isEmpty()) {
                Parked e = RING.poll();
                if (BY_POS.get(e.origin) != e) continue;   // (taken or replaced meanwhile)
                BY_POS.remove(e.origin);
                parkedVertices -= e.vertices;
                release(e);
                EVICTED.incrementAndGet();
            }
        }
        PARKED.incrementAndGet();
    }

    /** a compile task (worker) for section s whose inputs hash to hash: the parked mesh at its position, taken, or null */
    public static boolean take(SectionRenderDispatcher.RenderSection s, long hash) {
        if (!ON) return false;
        long pos = s.getOrigin().asLong();
        Parked p;
        synchronized (LOCK) {
            p = BY_POS.get(pos);
            if (p == null) return false;
            if (p.hash != hash) {
                // other inputs now - often a diagonal neighbour column not back yet (the section is meshed again when
                // it comes): the parked mesh stays for that compile; the budget evicts it if none matches
                STALE.incrementAndGet();
                return false;
            }
            BY_POS.remove(pos);
            parkedVertices -= p.vertices;
        }
        TAKEN.incrementAndGet();
        MeshMemo.Holder hd = (MeshMemo.Holder) s;
        if (MeshMemo.VERIFY) {   // mesh anyway; committed() compares with the parked mesh's checksums
            if (p.crcs != null) {
                long[] crcs = hd.sleep$crcs();
                synchronized (crcs) {
                    System.arraycopy(p.crcs, 0, crcs, 0, 8);
                    System.arraycopy(p.crcs, 16, crcs, 16, 20);
                }
                hd.sleep$setExpectSame(true);
            }
            RELEASE.add(p);
            return false;
        }
        ADOPT.add(new Object[] {s, p, ((ChunkMultiDraw.ChunkEntries) s).sleep$epoch()});
        return true;
    }

    /** render thread, before drawing (ChunkMultiDraw): taken meshes put back, stale ones released */
    static void drain() {
        Parked r;
        while ((r = RELEASE.poll()) != null) release(r);
        Object[] a;
        while ((a = ADOPT.poll()) != null) {
            var s = (SectionRenderDispatcher.RenderSection) a[0];
            Parked p = (Parked) a[1];
            int epoch = (Integer) a[2];
            // still there, not marked dirty or moved since the take, nothing compiled meanwhile: vanilla's compile would
            // have set exactly this mesh
            if (s.getOrigin().asLong() == p.origin && ((ChunkMultiDraw.ChunkEntries) s).sleep$epoch() == epoch
                    && s.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                Object[] md = ((ChunkMultiDraw.ChunkEntries) s).sleep$md();
                System.arraycopy(p.md, 0, md, 0, md.length);
                if (LightVolumes.ON) LightVolumes.adopted(s, p.light);
                ((MeshMemo.Holder) s).sleep$setIncoming(p.hash);
                s.setCompiled(p.compiled);
                SectionLists.changed(s);
                ADOPTED.incrementAndGet();
            } else {   // a newer state: it is (or will be) compiled anew
                release(p);
                DROPPED.incrementAndGet();
            }
        }
    }

    private static void release(Parked p) {
        for (Object o : p.md)
            if (o instanceof ChunkMultiDraw.Entry e) {
                e.arena.release(e.start, e.vertices);
                OcclusionCull.freeId(e.id);
                e.id = -1;
            }
    }

    public static String report() {
        synchronized (LOCK) {
            return String.format(java.util.Locale.ROOT, "mesh park: resets %d (never meshed %d, empty %d, meshed without a memo %d), parked %d (refused %d), taken %d (stale %d), put back %d, dropped %d, evicted %d; now %d sections, %.1f MB",
                    RESETS.get(), NEVER_MESHED.get(), EMPTY.get(), NO_MEMO.get(), PARKED.get(), REFUSED.get(), TAKEN.get(), STALE.get(), ADOPTED.get(), DROPPED.get(), EVICTED.get(), BY_POS.size(), parkedVertices * 32 / 1048576.0);
        }
    }
}
