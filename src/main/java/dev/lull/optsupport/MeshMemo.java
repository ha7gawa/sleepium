package dev.lull.optsupport;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dlab.meshMemo (11-17): a section whose mesh inputs are what they were at its current mesh is not meshed again.
 *
 * <p>Flying in circles at distance 32 the server tells the client to forget the chunks leaving the view and sends them
 * again moments later (89 % of the chunks received in the zoo orbit); every arrival marks the chunk's sections and
 * their neighbours dirty, once for the blocks and again for the light (ClientPacketListener.enableChunkLight,
 * readSectionList). 54 % of the sections meshed then had the same blocks and light as at their last mesh - each
 * meshed anew on the workers, uploaded, and handed to the frame thread (lists, the occlusion graph).
 *
 * <p>The inputs, hashed on the compile task's thread before vanilla meshes: the region's block-state copies of the
 * 3 x 3 x 3 sections around (their palettes and packed storage), both light layers of those sections (the raw bytes;
 * a missing sky layer by its values where the 18^3 box reads it), and the live level's biome containers of the same
 * sections (the tint reads the live level within the blend radius). Hashed again after the mesh: inputs that moved
 * meanwhile keep no memo. Not memoised: a section with block entities (mods' model data), with mods' extra geometry, or
 * with a translucent layer (its quads are sorted for the camera of the compile).
 * -Dlab.meshMemo.verify=true: a hit is meshed anyway and each layer's bytes compared with the kept mesh's.
 */
public final class MeshMemo {
    private MeshMemo() {}

    public static final boolean ON = Boolean.getBoolean("lab.meshMemo");
    public static final boolean VERIFY = Boolean.getBoolean("lab.meshMemo.verify");
    /** BlockTintCache invalidations (MeshMemoTintMixin), counted before and after each */
    public static final AtomicLong TINT_EPOCH = new AtomicLong(), TINT_MOVED = new AtomicLong(), LIGHT_EPOCH = new AtomicLong(), LIGHT_MOVED = new AtomicLong();
    public static final AtomicLong HASHED = new AtomicLong(), SKIPPED = new AtomicLong(), NOT_MEMO = new AtomicLong(), MOVED = new AtomicLong(),
            HASH_NANOS = new AtomicLong(), VERIFIED = new AtomicLong(), MISMATCHES = new AtomicLong();
    private static volatile boolean broken;

    /** RenderSection: the hash of its current mesh's inputs (0: none), the hash a compile in flight hands to setCompiled */
    public interface Holder {
        long sleep$memo();

        void sleep$setMemo(long h);

        long sleep$incoming();

        void sleep$setIncoming(long h);

        /** verify: the compile in flight is expected to give the kept mesh's bytes */
        boolean sleep$expectSame();

        void sleep$setExpectSame(boolean b);

        long[] sleep$crcs();
    }

    /** RebuildTask: the section it meshes (set where the section makes it) */
    public interface Task {
        void sleep$setSection(SectionRenderDispatcher.RenderSection s);
    }

    // ------------------------------------------------------------------ fields (the game runs with Mojang's names)

    private static Field REGION_CHUNKS, RC_SECTIONS, RC_WRAPPED, RC_BES, PC_DATA, DATA_STORAGE, DATA_PALETTE;

    private static boolean fields() {
        if (PC_DATA != null) return true;
        if (broken) return false;
        try {
            REGION_CHUNKS = field(RenderChunkRegion.class, "chunks");
            Class<?> rc = Class.forName("net.minecraft.client.renderer.chunk.RenderChunk");
            RC_SECTIONS = field(rc, "sections");
            RC_WRAPPED = field(rc, "wrapped");
            RC_BES = field(rc, "blockEntities");
            Class<?> data = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            DATA_STORAGE = field(data, "storage");
            DATA_PALETTE = field(data, "palette");
            PC_DATA = field(PalettedContainer.class, "data");
            return true;
        } catch (Throwable t) {
            broken = true;
            System.out.println("[sleep/opt] mesh memo: off (" + t + ")");
            return false;
        }
    }

    private static Field field(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object successful;

    /** SectionRenderDispatcher.SectionTaskResult.SUCCESSFUL (not public) */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object successful() {
        if (successful == null) {
            try {
                successful = Enum.valueOf((Class) Class.forName("net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionTaskResult"), "SUCCESSFUL");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return successful;
    }

    // ------------------------------------------------------------------ the hash

    private static long mix(long h, long v) {
        h = (h ^ v) * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 31);
    }

    private static long container(long h, Object c) throws IllegalAccessException {
        if (c == null) return mix(h, 0xA1A1L);
        Object d = PC_DATA.get(c);
        net.minecraft.util.BitStorage st = (net.minecraft.util.BitStorage) DATA_STORAGE.get(d);
        net.minecraft.world.level.chunk.Palette<?> p = (net.minecraft.world.level.chunk.Palette<?>) DATA_PALETTE.get(d);
        h = mix(h, st.getBits());
        for (long v : st.getRaw()) h = mix(h, v);
        if (p instanceof net.minecraft.world.level.chunk.GlobalPalette<?>) return mix(h, 0x61);
        int n = p.getSize();
        h = mix(h, n);
        for (int i = 0; i < n; i++) h = mix(h, System.identityHashCode(p.valueFor(i)));
        return h;
    }

    private static long layer(long h, DataLayer l) {
        if (l == null) return mix(h, 0xB0B0L);
        if (l.isDefinitelyHomogenous()) return mix(h, 0x100 | l.get(0, 0, 0));
        byte[] b = l.getData();
        long a = 0;
        for (int i = 0; i < b.length; i += 8) {
            a = (b[i] & 0xFFL) | (b[i + 1] & 0xFFL) << 8 | (b[i + 2] & 0xFFL) << 16 | (b[i + 3] & 0xFFL) << 24
                    | (b[i + 4] & 0xFFL) << 32 | (b[i + 5] & 0xFFL) << 40 | (b[i + 6] & 0xFFL) << 48 | (b[i + 7] & 0xFFL) << 56;
            h = mix(h, a);
        }
        return h;
    }

    /** the inputs of section s's mesh from region (0: not memoised) */
    public static long inputs(SectionRenderDispatcher.RenderSection s, RenderChunkRegion region, Level level, boolean extraGeometry) {
        if (extraGeometry || !fields()) return 0;
        long t0 = System.nanoTime();
        try {
            BlockPos o = s.getOrigin();
            int cx = o.getX() >> 4, cy = o.getY() >> 4, cz = o.getZ() >> 4;
            Object[] chunks = (Object[]) REGION_CHUNKS.get(region);
            if (chunks == null || chunks.length != 9) return 0;
            // block entities in the section: mods' model data and their own render paths - no memo
            Map<?, ?> bes = (Map<?, ?>) RC_BES.get(chunks[4]);
            for (Object k : bes.keySet()) {
                BlockPos p = (BlockPos) k;
                if (p.getY() >> 4 == cy) return 0;
            }
            long h = 0x243F6A8885A308D3L;
            // shader light: recipes or baked light, by the section's place in LightVolumes' dynamic set (it grows in play)
            if (LightVolumes.ON) h = mix(h, LightVolumes.recipesFor(SectionPos.asLong(cx, cy, cz)) ? 0x7E : 0x7F);
            for (Object rc : chunks) {
                LevelChunk w = (LevelChunk) RC_WRAPPED.get(rc);
                if (w instanceof EmptyLevelChunk) {
                    h = mix(h, 0xE0E0L);
                    continue;
                }
                List<?> secs = (List<?>) RC_SECTIONS.get(rc);
                for (int sy = cy - 1; sy <= cy + 1; sy++) {
                    int i = w.getSectionIndexFromSectionY(sy);
                    h = container(h, secs == null || i < 0 || i >= secs.size() ? null : secs.get(i));
                }
            }
            var light = level.getLightEngine();
            LayerLightEventListener bl = light.getLayerListener(LightLayer.BLOCK), sl = light.getLayerListener(LightLayer.SKY);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        SectionPos p = SectionPos.of(cx + dx, cy + dy, cz + dz);
                        h = layer(h, visible(bl, p));
                        DataLayer sky = visible(sl, p);
                        if (sky != null) h = layer(h, sky);
                        else {   // its values where the 18^3 box reads them (a missing sky layer: light from above)
                            int x0 = dx < 0 ? 15 : 0, x1 = dx > 0 ? 0 : 15, y0 = dy < 0 ? 15 : 0, y1 = dy > 0 ? 0 : 15, z0 = dz < 0 ? 15 : 0, z1 = dz > 0 ? 0 : 15;
                            for (int y = y0; y <= y1; y++)
                                for (int z = z0; z <= z1; z++)
                                    for (int x = x0; x <= x1; x++) {
                                        m.set(p.minBlockX() + x, p.minBlockY() + y, p.minBlockZ() + z);
                                        h = mix(h, sl.getLightValue(m));
                                    }
                        }
                    }
            // the tint reads the live level's biomes within the blend radius
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    LevelChunk c = level.getChunkSource().getChunk(cx + dx, cz + dz, false);
                    if (c == null) {
                        h = mix(h, 0xC0C0L);
                        continue;
                    }
                    for (int sy = cy - 1; sy <= cy + 1; sy++) {
                        int i = c.getSectionIndexFromSectionY(sy);
                        h = container(h, i < 0 || i >= c.getSectionsCount() ? null : c.getSection(i).getBiomes());
                    }
                }
            HASHED.incrementAndGet();
            return h == 0 ? 1 : h;
        } catch (Throwable t) {
            if (!broken) System.out.println("[sleep/opt] mesh memo: off (" + t + ")");
            broken = true;
            return 0;
        } finally {
            HASH_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    /**
     * The light layer a compile reads: the storage's visible copy (LightEngine.getLightValue -> getDataLayer(pos, false)).
     * getDataLayerData gives the packet's queued layer before runLightUpdates applies it - newer than what the compile
     * reads, which made a memo's hash and its mesh disagree (and needed the global LIGHT_EPOCH guard, 11-18).
     */
    private static DataLayer visible(LayerLightEventListener l, SectionPos p) {
        if (l instanceof net.minecraft.world.level.lighting.LightEngine<?, ?> e) return e.storage.getDataLayer(p.asLong(), false);
        return l.getDataLayerData(p);
    }

    /** -Dlab.meshMemo.lightGuard=true: also no memo for a compile that ran across any light batch (the old, global guard) */
    public static final boolean LIGHT_GUARD = Boolean.getBoolean("lab.meshMemo.lightGuard");

    /** a section with a translucent layer: not memoised (its quads were sorted for that compile's camera) */
    public static boolean translucent(SectionRenderDispatcher.RenderSection s) {
        return !s.getCompiled().isEmpty(RenderType.translucent());
    }

    // ------------------------------------------------------------------ verify

    /** VertexBuffer.upload of a section layer (render thread): the bytes' hash, compared when a hit was meshed anyway */
    public static void uploaded(SectionRenderDispatcher.RenderSection s, RenderType layer, com.mojang.blaze3d.vertex.MeshData mesh) {
        if (!VERIFY || !(s instanceof Holder hd)) return;
        int li = layer == RenderType.solid() ? 0 : layer == RenderType.cutoutMipped() ? 1 : layer == RenderType.cutout() ? 2 : layer == RenderType.tripwire() ? 3 : -1;
        if (li < 0) return;
        java.nio.ByteBuffer vb = mesh.vertexBuffer();
        long a = MemoryUtil.memAddress(vb), h = 0x5bd1e995L ^ vb.remaining();
        int n = vb.remaining();
        // (the normal's padding byte - every vertex's byte 31 - is never written by BufferBuilder: leftover memory, read
        // by no shader; masked)
        for (int i = 0; i + 8 <= n; i += 8) h = mix(h, MemoryUtil.memGetLong(a + i) & ((i & 31) == 24 ? 0x00FFFFFFFFFFFFFFL : -1L));
        for (int i = n & ~7; i < n; i++) h = mix(h, MemoryUtil.memGetByte(a + i));
        long[] crcs = hd.sleep$crcs();   // [0..3] the set mesh's layers, [4..7] their lengths, [8..11] / [12..15] this compile's
        long[] fh = new long[5];          // per vertex field: position, colour, uv, light, normal (xyz)
        for (int v = 0; v + 32 <= n; v += 32) {
            long p = a + v;
            fh[0] = mix(mix(fh[0], MemoryUtil.memGetLong(p)), MemoryUtil.memGetInt(p + 8));
            fh[1] = mix(fh[1], MemoryUtil.memGetInt(p + 12));
            fh[2] = mix(fh[2], MemoryUtil.memGetLong(p + 16));
            fh[3] = mix(fh[3], MemoryUtil.memGetInt(p + 24));
            fh[4] = mix(fh[4], MemoryUtil.memGetInt(p + 28) & 0xFFFFFF);
        }
        synchronized (crcs) {
            crcs[8 + li] = h;
            crcs[12 + li] = n;
            System.arraycopy(fh, 0, crcs, 36 + 5 * li, 5);
        }
        if (sampled(s)) {   // the bytes too, for where a mismatch differs
            byte[] copy = new byte[n];
            MemoryUtil.memByteBuffer(a, n).get(copy);
            for (int i = 31; i < n; i += 32) copy[i] = 0;
            BYTES.put(key(s, li, true), copy);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, byte[]> BYTES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong[] FIELD_DIFFS = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private static final int SAMPLE = Integer.getInteger("lab.meshMemo.sample", 16);

    private static boolean sampled(SectionRenderDispatcher.RenderSection s) {
        BlockPos o = s.getOrigin();
        return Math.floorMod((o.getX() >> 4) * 31 + (o.getZ() >> 4) * 17 + (o.getY() >> 4), SAMPLE) == 0;
    }

    private static long key(SectionRenderDispatcher.RenderSection s, int li, boolean pending) {
        return SectionPos.asLong(s.getOrigin().getX() >> 4, s.getOrigin().getY() >> 4, s.getOrigin().getZ() >> 4) * 16 + li * 2 + (pending ? 1 : 0);
    }

    /** a sampled mismatch: which vertex fields differ (BLOCK: position, colour, uv0, light, normal) */
    private static String diff(SectionRenderDispatcher.RenderSection s, int li) {
        byte[] old = BYTES.get(key(s, li, false)), now = BYTES.get(key(s, li, true));
        if (old == null || now == null || old.length != now.length) return "";
        int[] f = new int[5];
        int first = -1;
        for (int i = 0; i < old.length; i++)
            if (old[i] != now[i]) {
                if (first < 0) first = i;
                int o = i % 32;
                f[o < 12 ? 0 : o < 16 ? 1 : o < 24 ? 2 : o < 28 ? 3 : 4]++;
            }
        for (int k = 0; k < 5; k++) FIELD_DIFFS[k].addAndGet(f[k]);
        return String.format(java.util.Locale.ROOT, " (first at byte %d, differing bytes: position %d, colour %d, uv %d, light %d, normal %d)", first, f[0], f[1], f[2], f[3], f[4]);
    }

    /** setCompiled (verify): this compile's uploaded layers become the set mesh's - compared first when a hit was meshed anyway */
    public static void committed(SectionRenderDispatcher.RenderSection s, SectionRenderDispatcher.CompiledSection compiled, boolean expectSame) {
        long[] crcs = ((Holder) s).sleep$crcs();
        RenderType[] layers = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.tripwire()};
        synchronized (crcs) {
            for (int li = 0; li < 4; li++) {
                boolean has = !compiled.isEmpty(layers[li]);
                long h = has ? crcs[8 + li] : 0, n = has ? crcs[12 + li] : 0;
                if (expectSame) {
                    VERIFIED.incrementAndGet();
                    if ((crcs[li] != h || crcs[4 + li] != n)) {
                        String d = sampled(s) ? diff(s, li) : "";
                        String[] names = {"position", "colour", "uv", "light", "normal"};
                        StringBuilder fd = new StringBuilder(" fields differ:");
                        for (int k = 0; k < 5; k++) if (has && crcs[16 + 5 * li + k] != crcs[36 + 5 * li + k]) fd.append(' ').append(names[k]);
                        d += fd;
                        if (MISMATCHES.incrementAndGet() <= 12 || !d.isEmpty() && MISMATCHES.get() <= 40)
                            System.out.println("[sleep/opt] mesh memo MISMATCH at " + s.getOrigin() + " layer " + li + ": bytes " + crcs[4 + li] + " -> " + n + d);
                    }
                }
                if (sampled(s)) {
                    byte[] now = BYTES.remove(key(s, li, true));
                    if (has && now != null) BYTES.put(key(s, li, false), now);
                    else BYTES.remove(key(s, li, false));
                }
                crcs[li] = h;
                crcs[4 + li] = n;
                crcs[8 + li] = 0;
                crcs[12 + li] = 0;
                for (int k = 0; k < 5; k++) {
                    crcs[16 + 5 * li + k] = has ? crcs[36 + 5 * li + k] : 0;
                    crcs[36 + 5 * li + k] = 0;
                }
            }
        }
    }

    public static String report() {
        long hn = HASHED.get();
        return String.format(java.util.Locale.ROOT, "mesh memo: hashed %d (%.0f us each), skipped %d, not memoised %d, inputs moved during the mesh %d, tint caches invalidated during it %d, light updated during it %d%s",
                hn, HASH_NANOS.get() / 1e3 / Math.max(1, hn), SKIPPED.get(), NOT_MEMO.get(), MOVED.get(), TINT_MOVED.get(), LIGHT_MOVED.get(),
                VERIFY ? String.format(java.util.Locale.ROOT, ", verified layers %d, mismatches %d (sampled differing bytes: position %d, colour %d, uv %d, light %d, normal %d)", VERIFIED.get(), MISMATCHES.get(),
                        FIELD_DIFFS[0].get(), FIELD_DIFFS[1].get(), FIELD_DIFFS[2].get(), FIELD_DIFFS[3].get(), FIELD_DIFFS[4].get()) : "");
    }
}
