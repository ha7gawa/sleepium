package dev.lull.optsupport;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.lwjgl.opengl.GL;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * exp/chunk-multidraw (client): the opaque chunk layers (solid, cutout_mipped, cutout) drawn with one
 * glMultiDrawElementsBaseVertex per region of 8×4×8 sections instead of one bind + uniform + draw per section.
 *
 * <p>Each section's vertices are also copied, unchanged, into its region's arena buffer (one per region and layer; a
 * first-fit free list; grown with glCopyBufferSubData). The per-section offset that vanilla uploads as the ChunkOffset
 * uniform before every draw is written, as the same float values, into a uniform block indexed by gl_DrawIDARB: the
 * patched vertex shader adds exactly the same vec3 to the same Position, so the output is bit-identical (the frame hash
 * checks it). Needs GL 4.6 or ARB_shader_draw_parameters and the vanilla chunk shader sources (a resource pack's own
 * shader is not patched: that layer keeps vanilla's path). Sections without an arena entry are drawn the vanilla way.
 */
public final class ChunkMultiDraw {
    /** draws per multi-draw call = offsets per uniform block (16 KB, the guaranteed minimum block size) */
    public static final int MAX_DRAWS = 1024;
    /**
     * -Dlab.chunkmd.faceCull=true: the face buckets (direction culling). Off by default on 1.20.1 (on in 1.21.1, where
     * -Dlab.chunkmd.noFaceCull turned it off): the buckets reorder a section's quads, and on 1.20.1's terrain view
     * (saved world, 40 110 40) that alone - every bucket still drawn - changed ~270 pixels of leaves against vanilla
     * (fragments of equal depth resolved in another order), whichever directions were left out. Without it the quads
     * keep vanilla's order (all in "other"); the GPU culls the back faces as before.
     */
    private static final boolean FACE_CULL = Boolean.getBoolean("lab.chunkmd.faceCull") && !Boolean.getBoolean("lab.chunkmd.noFaceCull");
    private static final int REGION_W = 8, REGION_H = 4;
    private static final int UBO_BINDING = 7;
    /** vertex shader names patched by ChunkShaderPatchMixin */
    public static final Set<String> PATCHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** exp/shader-light: programs patched to take light from recipes and volumes */
    public static final Set<String> LIGHT_PATCHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static final Set<String> SUPPORTED = Set.of("rendertype_solid", "rendertype_cutout_mipped", "rendertype_cutout");
    public static long multiDraws, sectionsMulti, sectionsVanilla, sectionsSkipped, uploads, grows, quadsTotal, quadsDrawn;

    private static Boolean capable;

    private ChunkMultiDraw() {}

    /** GL 4.6 or ARB_shader_draw_parameters (render thread). */
    public static boolean capable() {
        Boolean c = capable;
        if (c == null) {
            GLCapabilities caps = GL.getCapabilities();
            capable = c = caps.OpenGL46 || caps.GL_ARB_shader_draw_parameters;
            System.out.println("[sleep/opt] chunk multidraw: " + (c ? "available" : "not available (needs GL 4.6 or ARB_shader_draw_parameters)"));
        }
        return c;
    }

    /** Shader source patch: the vanilla chunk vertex shader, offset from the uniform block while multi-drawing. */
    public static String patch(String name, String src) {
        if ("rendertype_translucent".equals(name)) {
            // light volumes: the section's volume from the ProbeVolume uniform (vanilla's loop) or the draw's offset (ours)
            String lit = LightVolumes.patchTranslucent(src);
            if (!TRANSLUCENT || !ssbo() || !indirectPatched()) return lit;
            String d = "uniform vec3 ChunkOffset;", u = "vec3 pos = Position + ChunkOffset;", vol = "probeUV2(float(ProbeVolume))";
            if (!lit.startsWith("#version 150") || !lit.contains(d) || !lit.contains(u)) return lit;
            String out = lit.replaceFirst("#version 150", "#version 150\n#extension GL_ARB_shader_draw_parameters : require"
                            + (lit.contains("GL_ARB_shader_storage_buffer_object") ? "" : "\n#extension GL_ARB_shader_storage_buffer_object : require"))
                    .replace(d, d + "\nuniform int ProbeMultiDraw;\nlayout(std430) buffer ProbeChunkOffsets { vec4 ProbeOffsets[]; };")
                    .replace(u, "vec3 pos = Position; if (ProbeMultiDraw != 0) pos += ProbeOffsets[" + drawIndex() + "].xyz; else pos += ChunkOffset;");
            if (lit.contains(vol)) {   // multi-drawn: the volume from the draw's offset (statements: Veil reprints sources without parentheses)
                String line = "vertexColor = Color * minecraft_sample_lightmap(Sampler2, " + vol + ");";
                if (!out.contains(line)) return lit;
                out = out.replace(line, "float probe_vol = float(ProbeVolume); if (ProbeMultiDraw != 0) probe_vol = ProbeOffsets[" + drawIndex()
                        + "].w; vertexColor = Color * minecraft_sample_lightmap(Sampler2, probeUV2(probe_vol));");
            }
            translucentPatched = true;
            return out;
        }
        String decl = "uniform vec3 ChunkOffset;";
        String use = "vec3 pos = Position + ChunkOffset;";
        String uv = "texCoord0 = UV0;";
        if (!SUPPORTED.contains(name) || !src.startsWith("#version 150") || !src.contains(decl) || !src.contains(use) || !src.contains(uv)) return src;
        // packed (mode 2): integers as floats, minus a whole number, times a power of two: the stored floats exactly.
        // No 'readonly' on storage blocks: a memory qualifier needs GLSL 4.20 (Intel's compiler refuses it under 150)
        String out = src.replaceFirst("#version 150", "#version 150\n#extension GL_ARB_shader_draw_parameters : require"
                        + (ssbo() ? "\n#extension GL_ARB_shader_storage_buffer_object : require" : ""))
                .replace(decl, decl + "\nuniform int ProbeMultiDraw;\n" + (ssbo()
                        ? "layout(std430) buffer ProbeChunkOffsets { vec4 ProbeOffsets[]; };"
                        : "layout(std140) uniform ProbeChunkOffsets { vec4 ProbeOffsets[" + MAX_DRAWS + "]; };"))
                .replace(use, "vec3 pos = Position; if (ProbeMultiDraw == 2) pos = (Position - 2048.0) * 0.00390625; if (ProbeMultiDraw != 0) pos += ProbeOffsets[" + drawIndex() + "].xyz; else pos += ChunkOffset;")
                // (statements, no ternary inside an expression: Veil reprints vanilla shader sources and drops their parentheses)
                .replace(uv, "texCoord0 = ProbeMultiDraw == 2 ? UV0 * 0.0000152587890625 : UV0;");
        String light = "vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);";
        if (LightVolumes.ON && ssbo() && out.contains(light)) {
            out = out.replaceFirst("#extension GL_ARB_shader_draw_parameters : require", "#extension GL_ARB_shader_draw_parameters : require\n#extension GL_ARB_explicit_attrib_location : require")
                    .replace("void main() {", LightVolumes.glsl() + "\nvoid main() {")
                    .replace(light, "ivec2 probe_uv2 = ProbeMultiDraw != 0 ? probeUV2(ProbeOffsets[" + drawIndex() + "].w) : UV2; vertexColor = Color * minecraft_sample_lightmap(Sampler2, probe_uv2);");
            LIGHT_PATCHED.add(name);
        }
        PATCHED.add(name);
        return out;
    }

    // ------------------------------------------------------------------ arenas

    /**
     * Face buckets, in the order they are stored: -X, -Y, -Z, other, +X, +Y, +Z (a camera in one octant sees the three
     * positive or negative ones plus "other", often one contiguous run). bq[k] = first quad of bucket k, bq[7] = quads.
     */
    static final class Entry {
        Arena arena;
        int start, vertices, indexCount;
        /** exp/shader-light: the mesh has light recipes; the section's light volume (-1: none, baked light) */
        boolean recipes;
        int vol = -1;
        /** a translucent copy's indices: first index in its arena's index buffer (-1: none, sequential indices) */
        int idx = -1;
        long origin;
        final int[] bq = new int[8];
        /** per bucket: the face plane nearest the camera side that could see it (+ buckets min, - buckets max coordinate, section-relative) */
        final float[] plane = new float[7];
        /** OcclusionCull: the entry's visibility slot (-1: none) */
        int id = -1;
    }

    private static ByteBuffer scratch = MemoryUtil.memAlloc(1 << 20);
    private static int[] quadBucket = new int[1 << 14];

    /**
     * The bucket of each quad from its geometric normal (both triangles of the quad, in the draw's winding): exactly
     * axis-aligned -> that direction, anything else -> "other". Then the quads copied bucket by bucket, stable (the
     * order inside a bucket is vanilla's: coplanar overlays keep their order).
     */
    private static ByteBuffer bucketed(ByteBuffer vb, int quads, int stride, int[] bq, float[] plane) {
        int bytes = quads * 4 * stride;
        if (scratch.capacity() < bytes) {
            MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Math.max(bytes, scratch.capacity() * 2));
        }
        if (quadBucket.length < quads) quadBucket = new int[Math.max(quads, quadBucket.length * 2)];
        long base = MemoryUtil.memAddress(vb);
        int[] count = new int[7];
        for (int k = 0; k < 3; k++) {
            plane[k] = Float.NEGATIVE_INFINITY;
            plane[k + 4] = Float.POSITIVE_INFINITY;
        }
        for (int q = 0; q < quads; q++) {
            long v = base + (long) q * 4 * stride;
            int b = 3;
            if (FACE_CULL) {
                int n1 = axisNormal(v, v + stride, v + 2L * stride), n2 = axisNormal(v + 2L * stride, v + 3L * stride, v);
                if (n1 >= 0 && n1 == n2) {
                    b = n1;
                    // model elements may reach outside the block and the section: the bucket's own extreme plane
                    float c = MemoryUtil.memGetFloat(v + 4L * (b & 3));
                    if (b < 3) plane[b] = Math.max(plane[b], c);
                    else plane[b] = Math.min(plane[b], c);
                }
            }
            quadBucket[q] = b;
            count[b]++;
        }
        int[] next = new int[7];
        for (int k = 0, acc = 0; k < 7; k++) {
            bq[k] = acc;
            next[k] = acc;
            acc += count[k];
        }
        bq[7] = quads;
        long dst = MemoryUtil.memAddress(scratch);
        int quadBytes = 4 * stride;
        for (int q = 0; q < quads; q++) {
            int b = quadBucket[q];
            MemoryUtil.memCopy(base + (long) q * quadBytes, dst + (long) next[b]++ * quadBytes, quadBytes);
        }
        scratch.limit(bytes).position(0);
        return scratch;
    }

    /** Bucket of the triangle a, b, c by its normal (b - a) x (c - b): 0 -X, 1 -Y, 2 -Z, 4 +X, 5 +Y, 6 +Z, -1 not axis-aligned. */
    private static int axisNormal(long a, long b, long c) {
        float ax = MemoryUtil.memGetFloat(a), ay = MemoryUtil.memGetFloat(a + 4), az = MemoryUtil.memGetFloat(a + 8);
        float bx = MemoryUtil.memGetFloat(b), by = MemoryUtil.memGetFloat(b + 4), bz = MemoryUtil.memGetFloat(b + 8);
        float cx = MemoryUtil.memGetFloat(c), cy = MemoryUtil.memGetFloat(c + 4), cz = MemoryUtil.memGetFloat(c + 8);
        float ux = bx - ax, uy = by - ay, uz = bz - az, wx = cx - bx, wy = cy - by, wz = cz - bz;
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        if (ny == 0 && nz == 0 && nx != 0) return nx > 0 ? 4 : 0;
        if (nx == 0 && nz == 0 && ny != 0) return ny > 0 ? 5 : 1;
        if (nx == 0 && ny == 0 && nz != 0) return nz > 0 ? 6 : 2;
        return -1;
    }

    /**
     * Buffers an arena grew out of, deleted once the GPU is past the copy from them: deleting at once made Intel's driver
     * wait for that copy (an N100's teleport tour: 376 ms in 29 grows, up to 89 ms in one upload). A fence after the
     * copy, polled without waiting (reapRetired: each grow and each frame's first layer).
     */
    private static final java.util.ArrayDeque<long[]> RETIRED = new java.util.ArrayDeque<>();

    static void retire(int vbo, int ibo) {
        RETIRED.add(new long[] {vbo, ibo, org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)});
        reapRetired();
    }

    static void reapRetired() {
        long[] r;
        while ((r = RETIRED.peek()) != null) {
            int st = org.lwjgl.opengl.GL32C.glClientWaitSync(r[2], 0, 0);
            if (st != org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED && st != org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED
                    && st != org.lwjgl.opengl.GL32C.GL_WAIT_FAILED) break;
            org.lwjgl.opengl.GL32C.glDeleteSync(r[2]);
            if (r[0] != 0) GL15C.glDeleteBuffers((int) r[0]);
            if (r[1] != 0) GL15C.glDeleteBuffers((int) r[1]);
            RETIRED.poll();
        }
    }

    static final class Arena {
        final long key;
        final RenderType layer;
        int vbo, vao, capacity, stride;   // capacity in vertices
        boolean vaoStale = true;
        final TreeMap<Integer, Integer> free = new TreeMap<>();   // start -> length (vertices)
        // per-frame draw list
        int n, maxIndices, drawCap = MAX_DRAWS;
        long counts = MemoryUtil.nmemAlloc(4L * MAX_DRAWS), bases = MemoryUtil.nmemAlloc(4L * MAX_DRAWS), indices = MemoryUtil.nmemCalloc(MAX_DRAWS, 8);
        /** per draw: its entry's visibility slot (OcclusionCull) */
        long slots = MemoryUtil.nmemAlloc(4L * MAX_DRAWS);
        ByteBuffer offsets = MemoryUtil.memAlloc(16 * MAX_DRAWS);

        /** Storage-buffer offsets have no size limit: the draw list grows instead of being split. */
        void ensureDraws(int needed) {
            if (needed <= drawCap) return;
            int c = Math.max(needed, drawCap * 2);
            counts = MemoryUtil.nmemRealloc(counts, 4L * c);
            bases = MemoryUtil.nmemRealloc(bases, 4L * c);
            slots = MemoryUtil.nmemRealloc(slots, 4L * c);
            MemoryUtil.nmemFree(indices);
            indices = MemoryUtil.nmemCalloc(c, 8);
            ByteBuffer o = MemoryUtil.memAlloc(16 * c);
            MemoryUtil.memCopy(MemoryUtil.memAddress(offsets), MemoryUtil.memAddress(o), 16L * n);
            MemoryUtil.memFree(offsets);
            offsets = o;
            drawCap = c;
        }
        boolean listed;
        final boolean packed;
        int uboOffset;
        long cmdOffset;
        /** the other pass's draw list (a shadow pass), swapped in and out whole: the camera's kept lists stay intact */
        private int n2, maxIndices2, drawCap2, uboOffset2;
        private long counts2, bases2, indices2, cmdOffset2, slots2;
        private ByteBuffer offsets2;
        private boolean listed2;

        void swapLists() {
            if (offsets2 == null) {
                drawCap2 = MAX_DRAWS;
                counts2 = MemoryUtil.nmemAlloc(4L * MAX_DRAWS);
                bases2 = MemoryUtil.nmemAlloc(4L * MAX_DRAWS);
                slots2 = MemoryUtil.nmemAlloc(4L * MAX_DRAWS);
                indices2 = MemoryUtil.nmemCalloc(MAX_DRAWS, 8);
                offsets2 = MemoryUtil.memAlloc(16 * MAX_DRAWS);
            }
            int i = n; n = n2; n2 = i;
            i = maxIndices; maxIndices = maxIndices2; maxIndices2 = i;
            i = drawCap; drawCap = drawCap2; drawCap2 = i;
            i = uboOffset; uboOffset = uboOffset2; uboOffset2 = i;
            long l = counts; counts = counts2; counts2 = l;
            l = bases; bases = bases2; bases2 = l;
            l = slots; slots = slots2; slots2 = l;
            l = indices; indices = indices2; indices2 = l;
            l = cmdOffset; cmdOffset = cmdOffset2; cmdOffset2 = l;
            ByteBuffer b = offsets; offsets = offsets2; offsets2 = b;
            boolean z = listed; listed = listed2; listed2 = z;
        }

        /** translucent: the sections' own (sorted) indices, 2 ints of room per vertex at start * 2 */
        boolean indexed;
        int ibo;

        Arena(long key, RenderType layer, boolean packed) {
            this.key = key;
            this.layer = layer;
            this.packed = packed;
            if (packed) stride = PACKED_STRIDE;
        }

        int alloc(int vertices) {
            for (var e : free.entrySet()) {
                if (e.getValue() >= vertices) {
                    int s = e.getKey(), len = e.getValue();
                    free.remove(s);
                    if (len > vertices) free.put(s + vertices, len - vertices);
                    return s;
                }
            }
            return -1;
        }

        void release(int start, int vertices) {
            // merge with neighbours
            Integer prev = free.floorKey(start - 1);
            if (prev != null && prev + free.get(prev) == start) {
                start = prev;
                vertices += free.remove(prev);
            }
            Integer next = free.get(start + vertices);
            if (next != null) {
                free.remove(start + vertices);
                vertices += next;
            }
            free.put(start, vertices);
        }

        void grow(int needed) {
            int cap = Math.max(capacity * 2, capacity + needed + 4096);
            int oldVbo = 0, oldIbo = 0;
            int nb = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, nb);
            GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) cap * stride, GL15C.GL_STATIC_DRAW);
            if (vbo != 0) {
                GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, vbo);
                GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, (long) capacity * stride);
                oldVbo = vbo;
            }
            if (indexed) {
                int ni = GL15C.glGenBuffers();
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, ni);
                GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) cap * 8, GL15C.GL_STATIC_DRAW);
                if (ibo != 0) {
                    GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, ibo);
                    GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, (long) capacity * 8);
                    oldIbo = ibo;
                }
                ibo = ni;
            }
            release(capacity, cap - capacity);
            vbo = nb;
            capacity = cap;
            vaoStale = true;
            if (oldVbo != 0 || oldIbo != 0) retire(oldVbo, oldIbo);
            grows++;
        }
    }

    private static final Long2ObjectOpenHashMap<Arena>[] ARENAS = newMaps(), PACKED_ARENAS = newMaps();
    private static final List<RenderType> LAYERS = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static Long2ObjectOpenHashMap<Arena>[] newMaps() {
        Long2ObjectOpenHashMap<Arena>[] m = new Long2ObjectOpenHashMap[8];
        for (int i = 0; i < m.length; i++) m[i] = new Long2ObjectOpenHashMap<>();
        return m;
    }

    private static int layerIndex(RenderType t) {
        int i = LAYERS.indexOf(t);
        if (i < 0 && LAYERS.size() < 8) {
            LAYERS.add(t);
            i = LAYERS.size() - 1;
        }
        return i;
    }

    public static boolean layerSupported(RenderType t) {
        return t == RenderType.solid() || t == RenderType.cutoutMipped() || t == RenderType.cutout();
    }

    /**
     * The full-format arena of a section: with packing on, only the ~3 % of quads that do not pack exactly go there, so
     * their regions are 4x wider in x and z (32x4x32 sections) and there are few extra multi-draws.
     */
    /**
     * The packed arena of a section: -Dlab.chunkmd.bigRegions (default on) makes them 32x8x32 sections, so a layer has a
     * handful of arenas (each a VAO bind, index bind, offsets range and one indirect call) instead of dozens; a call
     * over 1024 draws is split as before. Off: 8x4x8.
     */
    private static long packedKey(long sx, long sy, long sz) {
        if (SINGLE && ssbo()) return 0;
        return BIG_REGIONS ? regionKey(sx >> 2, sy >> 1, sz >> 2) : regionKey(sx, sy, sz);
    }

    private static final boolean BIG_REGIONS = !"false".equals(System.getProperty("lab.chunkmd.bigRegions"));

    /** -Dlab.chunkmd.single (default on, with storage buffers): one arena per layer and format, one call each. */
    private static final boolean SINGLE = !"false".equals(System.getProperty("lab.chunkmd.single"));

    private static long fullKey(long sx, long sy, long sz) {
        if (SINGLE && ssbo()) return 0;
        return PACK ? regionKey(sx >> 2, BIG_REGIONS ? sy >> 1 : sy, sz >> 2) : regionKey(sx, sy, sz);
    }

    private static long regionKey(long sx, long sy, long sz) {
        return ((sx >> 3) & 0x1FFFFF) | (((sy >> 2) & 0x3FFFFF) << 21) | (((sz >> 3) & 0x1FFFFF) << 43);
    }

    /** From VertexBuffer.upload of a section's layer buffer, before vanilla consumes the mesh (render thread). */
    public static boolean store(ChunkRenderDispatcher.RenderChunk section, RenderType layer, BufferBuilder.RenderedBuffer mesh) {
        BufferBuilder.DrawState ds = mesh.drawState();
        if (layer == RenderType.translucent() && shaderPipeline()) {
            storeShadowCopy(section, layer, mesh);
            return false;   // vanilla's sorted buffer still draws the camera's pass
        }
        if (layer == RenderType.translucent()) {
            storeTranslucent(section, layer, mesh);
            return false;   // vanilla's buffer kept too: its resorts and any frame drawn the vanilla way
        }
        if (!layerSupported(layer) || !ds.sequentialIndex() || ds.mode() != VertexFormat.Mode.QUADS || !capable()) return false;
        release(section, layer);
        int li = layerIndex(layer);
        if (li < 0) return false;
        var o = section.getOrigin();
        long key = packedKey(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4), fk = fullKey(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
        Arena a = ARENAS[li].get(fk);
        if (a == null) ARENAS[li].put(fk, a = new Arena(fk, layer, false));
        int stride = ds.format().getVertexSize();
        if (a.stride == 0) a.stride = stride;
        if (a.stride != stride) return false;   // another format: vanilla path
        int verts = ds.vertexCount();
        Entry e = new Entry();
        ByteBuffer vb = mesh.vertexBuffer();
        if (PACK_STATS && stride == 32) packStats(vb, verts);
        if (positionFirst(ds.format()) && verts % 4 == 0) {
            vb = bucketed(vb, verts / 4, stride, e.bq, e.plane);
        } else {
            java.util.Arrays.fill(e.bq, 0, 4, 0);   // everything in "other"
            for (int k = 4; k < 8; k++) e.bq[k] = verts / 4;
        }
        e.origin = o.asLong();
        e.recipes = LightVolumes.hasRecipes(mesh);
        uploads++;
        // meshes with light recipes (exp/shader-light) keep the 32-byte format: the packed one has no room for them
        // (not under a shaderpack: its vertex prologue reads the quads' positions as BLOCK floats)
        if (PACK && !shaderPipeline() && ds.format() == DefaultVertexFormat.BLOCK && verts % 4 == 0 && !LightVolumes.hasRecipes(mesh)) {
            Entry pe = new Entry();
            pe.origin = e.origin;
            System.arraycopy(e.plane, 0, pe.plane, 0, 7);
            ByteBuffer full = split(vb, verts / 4, e.bq, pe.bq);   // packed quads into PACKED_SCRATCH, the rest returned
            int packedQuads = pe.bq[7], fullQuads = e.bq[7];
            if (packedQuads > 0) {
                Arena pa = PACKED_ARENAS[li].get(key);
                if (pa == null) PACKED_ARENAS[li].put(key, pa = new Arena(key, layer, true));
                put(pa, pe, packedScratch, packedQuads * 4, section);
            }
            if (fullQuads > 0) put(a, e, full, fullQuads * 4, section);
            quadsPacked += packedQuads;
            quadsFull += fullQuads;
            return true;
        }
        put(a, e, vb, verts, section);
        return true;
    }

    /**
     * A shaderpack's shadow pass draws the translucent layer from the arenas too: a copy of the section's translucent
     * vertices (its sorted order is in the index buffer; a resort only replaces that, so the copy stays right), drawn
     * unsorted with sequential indices - the shadow programs do not blend.
     */
    private static void storeShadowCopy(ChunkRenderDispatcher.RenderChunk section, RenderType layer, BufferBuilder.RenderedBuffer mesh) {
        BufferBuilder.DrawState ds = mesh.drawState();
        if (ds.mode() != VertexFormat.Mode.QUADS || ds.format() != DefaultVertexFormat.BLOCK || !capable()) return;
        release(section, layer);
        int li = layerIndex(layer);
        if (li < 0) return;
        Arena a = ARENAS[li].get(0L);
        if (a == null) {
            ARENAS[li].put(0L, a = new Arena(0L, layer, false));
            a.indexed = TRANSLUCENT;   // (the camera's translucent multi-draw shares it once the pack is off)
        }
        int stride = ds.format().getVertexSize();
        if (a.stride == 0) a.stride = stride;
        int verts = ds.vertexCount();
        if (verts % 4 != 0) return;
        Entry e = new Entry();
        ByteBuffer vb = bucketed(mesh.vertexBuffer(), verts / 4, stride, e.bq, e.plane);
        e.origin = section.getOrigin().asLong();
        uploads++;
        put(a, e, vb, verts, section);
    }

    /** a shadow pass can draw this layer through the arenas */
    public static boolean shadowLayer(RenderType t) {
        return layerSupported(t) || t == RenderType.translucent() && shaderPipeline() && capable() && ssbo();
    }

    private static void put(Arena a, Entry e, ByteBuffer data, int verts, ChunkRenderDispatcher.RenderChunk section) {
        int start = a.alloc(verts);
        if (start < 0) {
            a.grow(verts);
            start = a.alloc(verts);
        }
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, a.vbo);
        GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, (long) start * a.stride, data);
        e.start = start;
        e.vertices = verts;
        e.indexCount = verts / 4 * 6;
        e.arena = a;
        if (OcclusionCull.ON && e.id < 0) e.id = OcclusionCull.newId();
        ((ChunkEntries) section).sleep$md()[slot(a)] = e;
        SectionLists.changed(section);
    }

    // ------------------------------------------------------------------ packed vertices (16 bytes, bit-exact)

    /** -Dlab.chunkmd.noPack=true: every quad in the 32-byte vanilla format */
    private static final boolean PACK = !Boolean.getBoolean("lab.chunkmd.noPack");
    static final int PACKED_STRIDE = 16;
    public static long quadsPacked, quadsFull;
    private static ByteBuffer packedScratch = MemoryUtil.memAlloc(1 << 19), fullScratch = MemoryUtil.memAlloc(1 << 20);

    /**
     * Splits bucketed BLOCK quads (32 bytes per vertex) into packed ones (written to packedScratch, 16 bytes per vertex)
     * and the rest (returned, still 32 bytes), each keeping bucket order; fb / pb receive the bucket starts of each part.
     * A quad is packed only if every vertex survives exactly: position (x + 8) * 256 an integer in 0..65535, UV * 65536
     * an integer in 0..65535 (no -0.0), both light shorts <= 255. Layout: position u16x3 @0, light u8x2 @6,
     * colour rgba8 @8 (as stored), UV u16x2 @12. The Normal attribute is not read by the chunk shaders.
     */
    private static ByteBuffer split(ByteBuffer vb, int quads, int[] fb, int[] pb) {
        if (packedScratch.capacity() < quads * 64) {
            MemoryUtil.memFree(packedScratch);
            packedScratch = MemoryUtil.memAlloc(Math.max(quads * 64, packedScratch.capacity() * 2));
        }
        if (fullScratch.capacity() < quads * 128) {
            MemoryUtil.memFree(fullScratch);
            fullScratch = MemoryUtil.memAlloc(Math.max(quads * 128, fullScratch.capacity() * 2));
        }
        long src = MemoryUtil.memAddress(vb), pdst = MemoryUtil.memAddress(packedScratch), fdst = MemoryUtil.memAddress(fullScratch);
        int[] bucketStart = fb.clone();
        int np = 0, nf = 0;
        for (int k = 0; k < 7; k++) {
            pb[k] = np;
            fb[k] = nf;
            for (int q = bucketStart[k]; q < bucketStart[k + 1]; q++) {
                long v = src + 128L * q;
                if (packable(v) && packable(v + 32) && packable(v + 64) && packable(v + 96)) {
                    long d = pdst + 64L * np;
                    for (int i = 0; i < 4; i++) pack(v + 32L * i, d + 16L * i);
                    np++;
                } else {
                    MemoryUtil.memCopy(v, fdst + 128L * nf, 128);
                    nf++;
                }
            }
        }
        pb[7] = np;
        fb[7] = nf;
        packedScratch.limit(np * 64).position(0);
        fullScratch.limit(nf * 128).position(0);
        return fullScratch;
    }

    private static boolean exact(float f, double bias, double scale) {
        if (Float.floatToRawIntBits(f) == 0x80000000) return false;
        double d = ((double) f + bias) * scale;
        return d >= 0 && d <= 65535 && d == Math.rint(d);
    }

    private static boolean packable(long v) {
        int l = MemoryUtil.memGetInt(v + 24);
        return exact(MemoryUtil.memGetFloat(v), 8, 256) && exact(MemoryUtil.memGetFloat(v + 4), 8, 256) && exact(MemoryUtil.memGetFloat(v + 8), 8, 256)
                && exact(MemoryUtil.memGetFloat(v + 16), 0, 65536) && exact(MemoryUtil.memGetFloat(v + 20), 0, 65536)
                && (l & 0xFFFF) <= 255 && (l >>> 16) <= 255;
    }

    private static void pack(long v, long d) {
        for (int i = 0; i < 3; i++) MemoryUtil.memPutShort(d + 2L * i, (short) (int) (((double) MemoryUtil.memGetFloat(v + 4L * i) + 8) * 256));
        int l = MemoryUtil.memGetInt(v + 24);
        MemoryUtil.memPutByte(d + 6, (byte) (l & 0xFF));
        MemoryUtil.memPutByte(d + 7, (byte) ((l >>> 16) & 0xFF));
        MemoryUtil.memPutInt(d + 8, MemoryUtil.memGetInt(v + 12));
        MemoryUtil.memPutShort(d + 12, (short) (int) ((double) MemoryUtil.memGetFloat(v + 16) * 65536));
        MemoryUtil.memPutShort(d + 14, (short) (int) ((double) MemoryUtil.memGetFloat(v + 20) * 65536));
    }

    /** The vanilla buffer is not filled for arena layers (no duplicate VRAM) while the layer's shader is patched. */
    public static boolean replacesVanilla(RenderType layer) {
        // (a shaderpack's terrain programs take the multi-draw path too: ShaderPrologue gives them the offsets)
        return ACTIVE && !KEEP_VANILLA && PATCHED.size() == SUPPORTED.size() && layer != RenderType.translucent();
    }

    /**
     * Multi-draw replaces renderChunkLayer's body (1.21.1: renderSectionLayer), so it gives way when another mod's mixin
     * changes that method (Sable / Aeronautics draw ships there, Veil hooks it; Embeddium / Rubidium replace it): then
     * the layers, and the uploads, stay vanilla's.
     */
    public static final boolean ACTIVE = !MixinTouch.touched("net/minecraft/client/renderer/LevelRenderer", "renderChunkLayer");

    private static final boolean KEEP_VANILLA = Boolean.getBoolean("lab.chunkmd.keepVanilla");

    /** From RenderChunk.reset (origin change, release): every layer's entry. */
    public static void release(ChunkRenderDispatcher.RenderChunk section) {
        RegionDraw.release(section);
        for (RenderType t : LAYERS) release(section, t);
        if (LightVolumes.ON) LightVolumes.released(section);
    }

    /**
     * A new compile published (1.20.1: RebuildTask's compiled.set, seen right after it at
     * LevelRenderer.addRecentlyCompiledChunk): a layer the new compile does not have is gone — vanilla stops drawing it
     * (CompiledChunk.isEmpty) and no upload replaces its entry, so the entry is released here, at the same moment.
     */
    public static void compiled(ChunkRenderDispatcher.RenderChunk section, ChunkRenderDispatcher.CompiledChunk compiled) {
        // the compile's completion may run off the render thread: the arenas belong to the render thread, which drains this
        PENDING_COMPILED.add(new Object[] {section, compiled});
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> PENDING_COMPILED = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Render thread, before drawing: the entries of layers the sections' current compiles no longer have. */
    private static void drainCompiled() {
        Object[] p;
        while ((p = PENDING_COMPILED.poll()) != null) {
            var section = (ChunkRenderDispatcher.RenderChunk) p[0];
            var compiled = (ChunkRenderDispatcher.CompiledChunk) p[1];
            if (section.getCompiledChunk() != compiled) continue;   // a newer compile (or a reset) came since
            for (RenderType t : LAYERS) if (compiled.isEmpty(t)) release(section, t);
        }
    }

    private static void release(ChunkRenderDispatcher.RenderChunk section, RenderType layer) {
        int li = LAYERS.indexOf(layer);
        if (li < 0) return;
        Object[] md = ((ChunkEntries) section).sleep$md();
        for (int k = 2 * li; k < 2 * li + 2; k++) {
            Entry e = (Entry) md[k];
            if (e != null) {
                e.arena.release(e.start, e.vertices);
                OcclusionCull.freeId(e.id);
                e.id = -1;
                md[k] = null;
                SectionLists.changed(section);
            }
        }
    }

    /** A section's entry slot: layer index * 2, + 1 for the packed format. */
    private static int slot(Arena a) {
        return 2 * LAYERS.indexOf(a.layer) + (a.packed ? 1 : 0);
    }

    /** RenderChunk (ChunkSectionMixin): its arena entries by slot, so drawing needs no lookup. */
    public interface ChunkEntries {
        Object[] sleep$md();

        /** SectionLists: the section's index in the visible list of build b (-1: not visible in it). */
        int sleep$visIdx(int b);

        void sleep$setVis(int idx, int b);

        /** SectionLists: the build whose dirty list holds the section (no duplicates). */
        int sleep$dirtyBuild();

        void sleep$setDirtyBuild(int b);

        /** Bumped by every setDirty and reset (blocks or light of the section changed, or it moved): KineticResident. */
        int sleep$epoch();
    }

    // ------------------------------------------------------------------ drawing

    private static final List<Arena> LISTED = new ArrayList<>();
    private static final Map<Integer, int[]> PROGRAM_STATE = new java.util.HashMap<>();   // program -> {flagLocation}

    /** a program deleted (its id may come back for another): ProgramCaches */
    static void forgetProgram(int id) {
        PROGRAM_STATE.remove(id);
    }

    /**
     * Draws the layer's visible sections: arena sections queued per region, others (no entry, stale origin) the vanilla
     * way on the spot. Returns false when the layer or the current shader cannot use it (then the caller draws vanilla).
     */
    public static boolean draw(RenderType layer, Iterable<?> visible, double x, double y, double z,
            net.minecraft.client.renderer.ShaderInstance shader) {
        if (dev.lull.DrawStats.ON) {
            dev.lull.DrawStats.phaseBegin(4);
            // the camera's layers as their own sections (GPU time, pipeline statistics per layer)
            if (!allFaces) dev.lull.DrawStats.section(layer == RenderType.solid() ? "terrain/solid" : layer == RenderType.cutoutMipped() ? "terrain/cutout_mipped" : "terrain/cutout");
        }
        try {
            return draw0(layer, visible, x, y, z, shader);
        } finally {
            if (dev.lull.DrawStats.ON) dev.lull.DrawStats.phaseEnd(4);
        }
    }

    /**
     * A visible list's element as its render chunk: 1.20.1's LevelRenderer lists RenderChunkInfo (the chunk in its
     * package-private field, read through ChunkInfoAccessor); a caller with its own list may pass the chunks themselves.
     */
    public static ChunkRenderDispatcher.RenderChunk chunkOf(Object o) {
        return o instanceof ChunkRenderDispatcher.RenderChunk c ? c : ((dev.lull.opt.ChunkInfoAccessor) o).sleep$chunk();
    }

    /** 1.20.1's VertexFormat has no offset by element: the position is at 0 when it is the first element */
    private static boolean positionFirst(VertexFormat f) {
        var els = f.getElements();
        return !els.isEmpty() && els.get(0) == DefaultVertexFormat.ELEMENT_POSITION;
    }

    /** sleep's shaderpack pipeline runs a pack (dev/lull/shaders): its shadow pass's copies, the prologue's arena binding */
    private static boolean shaderPipeline() {
        return dev.lull.shaders.ShaderPipeline.active;
    }

    private static boolean draw0(RenderType layer, Iterable<?> visible, double x, double y, double z,
            net.minecraft.client.renderer.ShaderInstance shader) {
        int li = LAYERS.indexOf(layer);
        if (li < 0) return false;
        if (pathTune && !allFaces && layer == RenderType.solid()) PathTune.frame();

        if (!RETIRED.isEmpty()) reapRetired();
        if (!PENDING_COMPILED.isEmpty()) drainCompiled();
        int[] ps = programState(shader.getId());
        if (ps == null) return false;
        program = shader.getId();
        layerIndex = li;
        var uniform = shader.CHUNK_OFFSET;
        LISTED.clear();
        boolean replaces = replacesVanilla(layer);
        int fs = 2 * li;
        // with the vanilla buffers empty, only the sections with an entry matter: SectionLists keeps them, in order
        if (replaces && SECTION_LISTS && visible instanceof it.unimi.dsi.fastutil.objects.ObjectArrayList<?> vis)
            visible = SectionLists.layer(vis, li);
        // the same camera position over the same lists and contents gives the same draws: last frame's are drawn again
        // (offsets and face buckets depend on the position only, so turning the head keeps them)
        boolean reusable = REUSE && replaces && SECTION_LISTS && SINGLE && ssbo() && !allFaces;
        LayerCache lc = LCACHE[li];
        long ver = SectionLists.version();
        if (reusable && lc.ver == ver && lc.x == x && lc.y == y && lc.z == z) {
            LISTED.addAll(lc.arenas);
            if (STABLE && flushStable(layer, ps, lc)) {
                drawReuses++;
                return true;
            }
            flushAll(layer, ps, true);
            drawReuses++;
            return true;
        }
        // a shadow pass: its own kept lists, while the sections' contents, the camera, the shadow box's section set
        // (its signature) and the light-facing buckets are the same (the sun turns slowly: the buckets rarely change)
        boolean shadowReuse = allFaces && REUSE && replaces && SINGLE && ssbo();
        LayerCache sc = SCACHE[li];
        if (shadowReuse && sc.ver == ver && sc.x == x && sc.y == y && sc.z == z && sc.sig == shadowSignature && sc.faces == lightFaces) {
            LISTED.addAll(sc.arenas);
            flushAll(layer, ps, true);
            shadowReuses++;
            return true;
        }
        if (SORT_NEAR && !allFaces) visible = sortedNearFirst(visible, x, y, z);
        boolean cut = VoxelLod.cutting && !allFaces;   // the GPU terrain draws the opaque layers (VoxelStream)
        if (cut) VoxelLod.camera(x, z);
        for (Object info : visible) {
            ChunkRenderDispatcher.RenderChunk s = chunkOf(info);
            if (cut && VoxelLod.far(s.getOrigin())) continue;   // the GPU's section (unless vanilla draws it for mods)
            // the section's own entry slots: no emptiness test (an empty layer has no entry), no map lookups
            Object[] md = ((ChunkEntries) s).sleep$md();
            Entry e = (Entry) md[fs], pe = (Entry) md[fs + 1];
            if (e == null && pe == null && (replaces || s.getCompiledChunk().isEmpty(layer))) {
                if (replaces) sectionsSkipped++;
                continue;
            }
            var o = s.getOrigin();
            long origin = o.asLong();
            if (e != null && e.origin != origin) e = null;
            if (pe != null && pe.origin != origin) pe = null;
            Arena a = e == null ? null : e.arena, pa = pe == null ? null : pe.arena;
            float ox = (float) ((double) o.getX() - x), oy = (float) ((double) o.getY() - y), oz = (float) ((double) o.getZ() - z);
            if (e == null && pe == null) {
                if (replaces) {   // its vanilla buffer holds nothing current: nothing to draw
                    sectionsSkipped++;
                    continue;
                }
                // vanilla, now
                GL20C.glUniform1i(ps[0], 0);
                if (uniform != null) {
                    uniform.set(ox, oy, oz);
                    uniform.upload();
                }
                var vb = s.getBuffer(layer);
                vb.bind();
                vb.draw();
                sectionsVanilla++;
                continue;
            }
            if (pe != null) queue(pa, pe, o, x, y, z, ox, oy, oz, layer, ps);
            if (e != null) queue(a, e, o, x, y, z, ox, oy, oz, layer, ps);
            sectionsMulti++;
        }
        if (reusable) {
            lc.stable = false;
            lc.arenas.clear();
            lc.arenas.addAll(LISTED);
            lc.ver = ver;
            lc.x = x;
            lc.y = y;
            lc.z = z;
        } else if (!allFaces) {   // (a shadow pass's list leaves the camera's cache alone)
            lc.ver = -1;
            lc.stable = false;
        }
        if (shadowReuse) {
            sc.arenas.clear();
            sc.arenas.addAll(LISTED);
            sc.ver = ver;
            sc.x = x;
            sc.y = y;
            sc.z = z;
            sc.sig = shadowSignature;
            sc.faces = lightFaces;
        }
        flushAll(layer, ps, reusable || shadowReuse);
        return true;
    }

    /**
     * -Dlab.chunkmd.stable (default on; =false: uploaded every frame): a layer drawn again from its kept lists (the same camera position and
     * contents) keeps its offsets and indirect commands in buffers of its own, written once when the lists are kept,
     * instead of a new offsets store and a commands upload every frame (the driver's thread does that work).
     */
    private static final boolean STABLE = !"false".equals(System.getProperty("lab.chunkmd.stable"));
    public static long stableWrites;

    private static boolean flushStable(RenderType layer, int[] ps, LayerCache lc) {
        if (!ssbo() || !indirect() || OcclusionCull.usable() || allFaces) return false;
        List<Arena> as = lc.arenas;
        int n = as.size();
        if (!lc.stable) {
            if (uboAlign == 0) uboAlign = Math.max(16, GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
            if (lc.uboOffs.length < n) {
                lc.uboOffs = new int[n];
                lc.cmdOffs = new long[n];
            }
            int total = 0, cmds = 0;
            for (int i = 0; i < n; i++) {
                Arena a = as.get(i);
                lc.uboOffs[i] = total;
                lc.cmdOffs[i] = cmds * 20L;
                if (a.n == 0) continue;
                total += (16 * a.n + uboAlign - 1) / uboAlign * uboAlign;
                cmds += a.n;
            }
            if (total == 0) return false;
            if (staging.capacity() < total) {
                MemoryUtil.memFree(staging);
                staging = MemoryUtil.memAlloc(Math.max(total, staging.capacity() * 2));
            }
            long bytes = cmds * 20L;
            if (cmdStaging.capacity() < bytes) {
                MemoryUtil.memFree(cmdStaging);
                cmdStaging = MemoryUtil.memAlloc((int) Math.max(bytes, cmdStaging.capacity() * 2L));
            }
            long st = MemoryUtil.memAddress(staging), c = MemoryUtil.memAddress(cmdStaging);
            for (int i = 0; i < n; i++) {
                Arena a = as.get(i);
                if (a.n == 0) continue;
                MemoryUtil.memCopy(MemoryUtil.memAddress(a.offsets), st + lc.uboOffs[i], 16L * a.n);
                long p = c + lc.cmdOffs[i];
                for (int k = 0; k < a.n; k++, p += 20) {
                    MemoryUtil.memPutInt(p, MemoryUtil.memGetInt(a.counts + 4L * k));
                    MemoryUtil.memPutInt(p + 4, 1);
                    MemoryUtil.memPutInt(p + 8, 0);
                    MemoryUtil.memPutInt(p + 12, MemoryUtil.memGetInt(a.bases + 4L * k));
                    MemoryUtil.memPutInt(p + 16, k);
                }
            }
            if (lc.ssbo == 0) lc.ssbo = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, lc.ssbo);
            GL15C.nglBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, total + 16L, st, GL15C.GL_STATIC_DRAW);
            // commands in an immutable store (Intel draws indirect only from those), a new one per kept list
            if (lc.cmd != 0) GL15C.glDeleteBuffers(lc.cmd);
            lc.cmd = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, lc.cmd);
            org.lwjgl.opengl.GL44C.nglBufferStorage(GL40C.GL_DRAW_INDIRECT_BUFFER, bytes, c, 0);
            lc.stable = true;
            stableWrites++;
        }
        for (int i = 0; i < n; i++) {
            Arena a = as.get(i);
            a.listed = false;
            a.uboOffset = lc.uboOffs[i];
            a.cmdOffset = lc.cmdOffs[i];
        }
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, lc.cmd);
        if (LightVolumes.ON) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, LightVolumes.BINDING, LightVolumes.buffer());
        long tLoop = System.nanoTime();
        drawArenas(layer, ps, RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS), GL43C.GL_SHADER_STORAGE_BUFFER, lc.ssbo, true, 0, 0, true);
        loopNanos += System.nanoTime() - tLoop;
        GL20C.glUniform1i(ps[0], 0);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        return true;
    }

    // ------------------------------------------------------------------ the translucent layer

    /**
     * -Dlab.chunkmd.translucent (default on; =false: vanilla's loop): the translucent layer in one multi-draw too. Each section's vertices are copied into
     * one arena and its indices - vanilla's back-to-front sort, renewed by every resort - into the arena's index buffer;
     * the sections are drawn in vanilla's order (the visible list backwards) by one indirect call, whose commands keep
     * that order and each section's own index order, so blending sees the same sequence of triangles. Vanilla's own
     * buffers stay filled: a frame where any visible section has no current copy draws the vanilla way.
     */
    public static final boolean TRANSLUCENT = !"false".equals(System.getProperty("lab.chunkmd.translucent"));
    static volatile boolean translucentPatched;
    public static long translucentDraws, translucentFallbacks, resorts;
    private static ByteBuffer indexScratch = MemoryUtil.memAlloc(1 << 16);

    /** LevelRenderer.renderChunkLayer: the translucent layer can take the multi-draw path now */
    public static boolean translucentReady() {
        return TRANSLUCENT && translucentPatched && ACTIVE && !shaderPipeline() && capable() && ssbo() && indirect();
    }

    private static void storeTranslucent(ChunkRenderDispatcher.RenderChunk section, RenderType layer, BufferBuilder.RenderedBuffer mesh) {
        BufferBuilder.DrawState ds = mesh.drawState();
        release(section, layer);
        if (!TRANSLUCENT || !translucentPatched || ds.mode() != VertexFormat.Mode.QUADS || ds.format() != DefaultVertexFormat.BLOCK || !capable()) return;
        int verts = ds.vertexCount();
        if (verts % 4 != 0 || verts == 0) return;
        int li = layerIndex(layer);
        if (li < 0) return;
        Arena a = ARENAS[li].get(0L);
        if (a == null) {
            ARENAS[li].put(0L, a = new Arena(0L, layer, false));
            a.indexed = true;
        }
        if (a.stride == 0) a.stride = ds.format().getVertexSize();
        if (!a.indexed) return;
        Entry e = new Entry();
        java.util.Arrays.fill(e.bq, 0, 4, 0);   // no face buckets: the order is the section's indices
        for (int k = 4; k < 8; k++) e.bq[k] = verts / 4;
        e.origin = section.getOrigin().asLong();
        uploads++;
        put(a, e, mesh.vertexBuffer(), verts, section);
        e.idx = e.start * 2;
        e.indexCount = ds.indexCount();
        // (1.20.1: a sequential buffer's indexBuffer() is an empty slice, not null)
        writeIndices(a, e, ds.sequentialIndex() ? null : mesh.indexBuffer(), ds.indexType(), ds.indexCount());
    }

    /** a section's indices (vanilla's sorted buffer; null: the sequential quad pattern) as ints at its index range */
    private static void writeIndices(Arena a, Entry e, ByteBuffer src, VertexFormat.IndexType type, int count) {
        if (count > e.vertices * 2) {   // (never for quads: 1.5 per vertex)
            e.idx = -1;
            return;
        }
        if (indexScratch.capacity() < 4 * count) {
            MemoryUtil.memFree(indexScratch);
            indexScratch = MemoryUtil.memAlloc(Math.max(4 * count, indexScratch.capacity() * 2));
        }
        long d = MemoryUtil.memAddress(indexScratch);
        if (src == null) {
            for (int q = 0, i = 0; i < count; q += 4, i += 6) {
                MemoryUtil.memPutInt(d + 4L * i, q);
                MemoryUtil.memPutInt(d + 4L * i + 4, q + 1);
                MemoryUtil.memPutInt(d + 4L * i + 8, q + 2);
                MemoryUtil.memPutInt(d + 4L * i + 12, q + 2);
                MemoryUtil.memPutInt(d + 4L * i + 16, q + 3);
                MemoryUtil.memPutInt(d + 4L * i + 20, q);
            }
        } else {
            long sp = MemoryUtil.memAddress(src);
            if (type == VertexFormat.IndexType.SHORT) {
                if (src.remaining() < 2 * count) {
                    e.idx = -1;
                    return;
                }
                for (int i = 0; i < count; i++) MemoryUtil.memPutInt(d + 4L * i, Short.toUnsignedInt(MemoryUtil.memGetShort(sp + 2L * i)));
            } else {
                if (src.remaining() < 4 * count) {
                    e.idx = -1;
                    return;
                }
                MemoryUtil.memCopy(sp, d, 4L * count);
            }
        }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, a.ibo);
        GL15C.nglBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, 4L * e.idx, 4L * count, d);
    }

    /** an index-only VertexBuffer.upload of a translucent section (1.20.1's resort): its copy's indices replaced the same way */
    public static void resorted(ChunkRenderDispatcher.RenderChunk section, ByteBuffer indices, VertexFormat.IndexType type) {
        int li = LAYERS.indexOf(RenderType.translucent());
        if (li < 0 || indices == null || type == null) return;
        Entry e = (Entry) ((ChunkEntries) section).sleep$md()[2 * li];
        if (e == null || e.idx < 0 || !e.arena.indexed) return;
        int count = indices.remaining() / type.bytes;
        if (count != e.indexCount) {   // another count than the copy: no longer the same mesh - vanilla draws it
            release(section, RenderType.translucent());
            return;
        }
        writeIndices(e.arena, e, indices, type, count);
        resorts++;
    }

    /** -Dlab.chunkmd.translucentList=false: drawTranslucent walks every visible section again (A/B) */
    static final boolean TRANSLUCENT_LIST = !"false".equals(System.getProperty("lab.chunkmd.translucentList"));

    /**
     * The translucent layer's draw: the visible sections backwards (vanilla's order), one command each, one call.
     * False (nothing drawn): a visible non-empty section has no current copy - the caller draws the vanilla way.
     */
    public static boolean drawTranslucent(it.unimi.dsi.fastutil.objects.ObjectArrayList<?> visible, double x, double y, double z,
            net.minecraft.client.renderer.ShaderInstance shader) {
        RenderType layer = RenderType.translucent();
        int li = LAYERS.indexOf(layer);
        if (li < 0) return false;
        if (!PENDING_COMPILED.isEmpty()) drainCompiled();
        int[] ps = programState(shader.getId());
        if (ps == null) return false;
        // SectionLists: only the sections with a non-empty translucent layer, in the same order (the loop below skips the
        // rest anyway: a pass over every visible section's compiled layers was 9 % of the render thread)
        if (SECTION_LISTS && TRANSLUCENT_LIST) visible = SectionLists.translucent(visible);
        int cap = visible.size();
        if (staging.capacity() < 16 * cap + 16) {
            MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(Math.max(16 * cap + 16, staging.capacity() * 2));
        }
        if (cmdStaging.capacity() < 20 * cap) {
            MemoryUtil.memFree(cmdStaging);
            cmdStaging = MemoryUtil.memAlloc(Math.max(20 * cap, cmdStaging.capacity() * 2));
        }
        long st = MemoryUtil.memAddress(staging), c = MemoryUtil.memAddress(cmdStaging);
        int n = 0;
        Arena arena = null;
        for (int i = cap - 1; i >= 0; i--) {
            ChunkRenderDispatcher.RenderChunk s = chunkOf(visible.get(i));
            if (s.getCompiledChunk().isEmpty(layer)) continue;
            Entry e = (Entry) ((ChunkEntries) s).sleep$md()[2 * li];
            var o = s.getOrigin();
            if (e == null || e.idx < 0 || e.origin != o.asLong() || !e.arena.indexed || (arena != null && e.arena != arena)) {
                translucentFallbacks++;
                return false;
            }
            arena = e.arena;
            long p = st + 16L * n;
            MemoryUtil.memPutFloat(p, (float) ((double) o.getX() - x));
            MemoryUtil.memPutFloat(p + 4, (float) ((double) o.getY() - y));
            MemoryUtil.memPutFloat(p + 8, (float) ((double) o.getZ() - z));
            MemoryUtil.memPutFloat(p + 12, LightVolumes.translucentPatched ? LightVolumes.translucentVolume(s) : 0f);
            long q = c + 20L * n;
            MemoryUtil.memPutInt(q, e.indexCount);
            MemoryUtil.memPutInt(q + 4, 1);
            MemoryUtil.memPutInt(q + 8, e.idx);
            MemoryUtil.memPutInt(q + 12, e.start);
            MemoryUtil.memPutInt(q + 16, n);
            n++;
        }
        if (n == 0) return true;   // nothing visible to draw, as vanilla
        int buf = ssboBuffer();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buf);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 16L * n + 16, GL15C.GL_STREAM_DRAW);
        GL15C.nglBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, 16L * n, st);
        long cmdBase = COMMANDS.upload(c, 20L * n);
        if (LightVolumes.ON && LightVolumes.translucentPatched) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, LightVolumes.BINDING, LightVolumes.buffer());
        if (arena.vaoStale || arena.vao == 0) setupVao(arena, layer);
        GL30C.glBindVertexArray(arena.vao);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, arena.ibo);
        GL20C.glUniform1i(ps[0], 1);
        GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, UBO_BINDING, buf, 0, 16L * n);
        GL43C.glMultiDrawElementsIndirect(GL15C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, cmdBase, n, 20);
        GL20C.glUniform1i(ps[0], 0);
        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
        multiDraws++;
        translucentDraws++;
        sectionsMulti += n;
        return true;
    }

    /** -Dlab.chunkmd.reuse=false: build the draws every frame. */
    private static final boolean REUSE = !"false".equals(System.getProperty("lab.chunkmd.reuse"));
    public static long drawReuses;

    /** A layer's last draws (single arenas keep their lists when kept) and what they were built from. */
    static final class LayerCache {
        long ver = -1, sig;
        int faces;
        /** -Dlab.chunkmd.stable: the kept draws' offsets and commands in the layer's own buffers, written once */
        boolean stable;
        int ssbo, cmd;
        int[] uboOffs = new int[0];
        long[] cmdOffs = new long[0];
        double x = Double.NaN, y, z;
        final List<Arena> arenas = new ArrayList<>();
    }

    private static final LayerCache[] LCACHE = newCaches();

    private static LayerCache[] newCaches() {
        LayerCache[] c = new LayerCache[8];
        for (int i = 0; i < c.length; i++) c[i] = new LayerCache();
        return c;
    }

    private static ByteBuffer staging = MemoryUtil.memAlloc(1 << 16);
    private static int uboAlign;

    /**
     * The layer's queued draws: every arena's offsets in one orphaning upload (each arena then reads its own range),
     * packed arenas first and full-format ones after, so the mode uniform is set at most twice per layer.
     */
    public static long flushNanos, flushDraws, offNanos, cmdUpNanos, drawNanos, lvNanos, loopNanos;

    private static void flushAll(RenderType layer, int[] ps, boolean keep) {
        long t0 = System.nanoTime();
        try {
            flushAll0(layer, ps, keep);
        } finally {
            flushNanos += System.nanoTime() - t0;
        }
    }

    private static void flushAll0(RenderType layer, int[] ps, boolean keep) {
        boolean sb = ssbo();
        int target = sb ? GL43C.GL_SHADER_STORAGE_BUFFER : GL31C.GL_UNIFORM_BUFFER;
        if (uboAlign == 0) uboAlign = Math.max(16, GL11C.glGetInteger(sb ? GL43C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT : GL31C.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
        int total = 0;
        for (Arena a : LISTED) {
            a.listed = false;
            if (a.n == 0) continue;
            a.uboOffset = total;
            total += (16 * a.n + uboAlign - 1) / uboAlign * uboAlign;
            flushDraws += a.n;
        }
        if (total == 0) return;
        if (staging.capacity() < total) {
            MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(Math.max(total, staging.capacity() * 2));
        }
        long st = MemoryUtil.memAddress(staging);
        for (Arena a : LISTED) if (a.n > 0) MemoryUtil.memCopy(MemoryUtil.memAddress(a.offsets), st + a.uboOffset, 16L * a.n);
        long tOff = System.nanoTime();
        int buf = sb ? ssboBuffer() : ubo();
        GL15C.glBindBuffer(target, buf);
        // new storage each time (no wait on the previous frame's draws); a uniform block's full size past the last range
        GL15C.glBufferData(target, total + (sb ? 16L : 16L * MAX_DRAWS), GL15C.GL_STREAM_DRAW);
        staging.limit(total).position(0);
        GL15C.glBufferSubData(target, 0, staging);
        staging.clear();
        offNanos += System.nanoTime() - tOff;
        long tCmd = System.nanoTime();
        boolean indirect = indirect();
        long cmdBase = 0;
        occl = false;
        if (indirect) {
            // every arena's commands {count, 1 instance, first index 0, base vertex, base instance 0} in one upload
            int cmds = 0;
            for (Arena a : LISTED) {
                if (a.n == 0) continue;
                a.cmdOffset = cmds * 20L;
                cmds += a.n;
            }
            long bytes = cmds * 20L;
            if (cmdStaging.capacity() < bytes) {
                MemoryUtil.memFree(cmdStaging);
                cmdStaging = MemoryUtil.memAlloc((int) Math.max(bytes, cmdStaging.capacity() * 2L));
            }
            long c = MemoryUtil.memAddress(cmdStaging);
            occl = sb && !allFaces && OcclusionCull.usable();
            if (occl) {
                if (slotStaging.capacity() < 4 * cmds) {
                    MemoryUtil.memFree(slotStaging);
                    slotStaging = MemoryUtil.memAlloc(Math.max(4 * cmds, slotStaging.capacity() * 2));
                }
                long sl = MemoryUtil.memAddress(slotStaging);
                for (Arena a : LISTED) if (a.n > 0) MemoryUtil.memCopy(a.slots, sl + a.cmdOffset / 5, 4L * a.n);
                occlCmds = cmds;
            }
            for (Arena a : LISTED) {
                if (a.n == 0) continue;
                long p = c + a.cmdOffset;
                for (int i = 0; i < a.n; i++, p += 20) {
                    MemoryUtil.memPutInt(p, MemoryUtil.memGetInt(a.counts + 4L * i));
                    MemoryUtil.memPutInt(p + 4, 1);
                    MemoryUtil.memPutInt(p + 8, 0);
                    MemoryUtil.memPutInt(p + 12, MemoryUtil.memGetInt(a.bases + 4L * i));
                    MemoryUtil.memPutInt(p + 16, i);   // base instance = the draw's index: its offset (a GPU pass may drop draws)
                }
            }
            long tUp = System.nanoTime();
            cmdBase = COMMANDS.upload(c, bytes);
            cmdUpNanos += System.nanoTime() - tUp;
        }
        RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        long tLv = System.nanoTime();
        if (LightVolumes.ON && sb) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, LightVolumes.BINDING, LightVolumes.buffer());
        lvNanos += System.nanoTime() - tLv;
        long tLoop = System.nanoTime();
        if (occl && OcclusionCull.begin(layerIndex, MemoryUtil.memAddress(slotStaging), occlCmds, LISTED.size())) {
            // phase 1: what was visible last frame; the depth pyramid; phase 2: the rest that is visible now
            int idx = seq.type().asGLType;
            for (int phase = 1; phase <= 2; phase++) {
                if (phase == 2 && OcclusionCull.needHiZ()) OcclusionCull.buildHiZ(program);
                for (int j = 0; j < LISTED.size(); j++) {
                    Arena a = LISTED.get(j);
                    if (a.n == 0) continue;
                    OcclusionCull.cull(phase, j, COMMANDS.buffer(), cmdBase + a.cmdOffset, a.n, buf, a.uboOffset, (int) (a.cmdOffset / 20));
                }
                OcclusionCull.barrierForDraws(program);
                drawArenas(layer, ps, seq, target, buf, sb, phase);
            }
            OcclusionCull.stats();
            OcclusionCull.endLayer();
            if (!keep) for (Arena a : LISTED) {
                a.n = 0;
                a.maxIndices = 0;
            }
        } else drawArenas(layer, ps, seq, target, buf, sb, indirect ? 0 : -1, cmdBase, keep);
        loopNanos += System.nanoTime() - tLoop;
        GL20C.glUniform1i(ps[0], 0);
        if (indirect) GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
    }

    private static int program, layerIndex, occlCmds;
    private static boolean occl;
    private static ByteBuffer slotStaging = MemoryUtil.memAlloc(1 << 14);

    private static void drawArenas(RenderType layer, int[] ps, RenderSystem.AutoStorageIndexBuffer seq, int target, int buf, boolean sb, int phase) {
        drawArenas(layer, ps, seq, target, buf, sb, phase, 0, true);
    }

    /** phase: -1 the non-indirect fallback, 0 every command, 1 / 2 an occlusion phase's (OcclusionCull's buffers bound) */
    private static void drawArenas(RenderType layer, int[] ps, RenderSystem.AutoStorageIndexBuffer seq, int target, int buf, boolean sb, int phase, long cmdBase,
            boolean keep) {
        boolean indirect = phase >= 0;
        int mode = -1;
        for (int pass = 0; pass < 2; pass++) {
            for (Arena a : LISTED) {
                if (a.n == 0 || a.packed != (pass == 0)) continue;
                int m = a.packed ? 2 : 1;
                if (m != mode) GL20C.glUniform1i(ps[0], mode = m);
                if (a.vaoStale || a.vao == 0) setupVao(a, layer);
                GL30C.glBindVertexArray(a.vao);
                seq.bind(a.maxIndices);
                // a shaderpack's vertex prologue reads the quad's other vertices from the arena
                if (shaderPipeline()) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 9, a.vbo);
                GL30C.glBindBufferRange(target, UBO_BINDING, buf, a.uboOffset, sb ? 16L * a.n : 16L * MAX_DRAWS);
                if (dev.lull.DrawStats.ON) dev.lull.DrawStats.phaseBegin(3);
                if (phase > 0)   // the layer's commands, those this phase does not draw with no instance (the order kept)
                    OcclusionCull.draw(phase, LISTED.indexOf(a), seq.type().asGLType, a.n, (int) (a.cmdOffset / 20));
                else if (indirect) {
                    long tD = System.nanoTime();
                    GL43C.glMultiDrawElementsIndirect(GL15C.GL_TRIANGLES, seq.type().asGLType, cmdBase + a.cmdOffset, a.n, 20);
                    drawNanos += System.nanoTime() - tD;
                    if (IndirectDiag.ON) IndirectDiag.after("chunks", seq.type().asGLType, cmdBase + a.cmdOffset, a.n, 20);
                } else GL32C.nglMultiDrawElementsBaseVertex(GL15C.GL_TRIANGLES, a.counts, seq.type().asGLType, a.indices, a.n, a.bases);
                if (dev.lull.DrawStats.ON) dev.lull.DrawStats.phaseEnd(3);
                multiDraws++;
                if (!keep) {
                    a.n = 0;
                    a.maxIndices = 0;
                }
            }
        }
    }

    private static ByteBuffer cmdStaging = MemoryUtil.memAlloc(1 << 16);
    private static final IndirectCommands COMMANDS = new IndirectCommands();
    /** indirect possible here (GL 4.3 / ARB_multi_draw_indirect, not refused by a flag): decided once, before the shaders are patched */
    private static Boolean indirectOk;
    /** the path drawn now: changes only while PathTune measures */
    private static volatile boolean indirectNow;
    /** the shaders read the draw index either way (max of both): PathTune may switch the path between frames */
    private static boolean pathTune;
    /** -Dlab.chunkmd.pathTune=false: the vendor rule, never measured */
    private static final boolean PATH_TUNE = !"false".equals(System.getProperty("lab.chunkmd.pathTune"));
    static String pathChoice = "not measured";

    /**
     * GL 4.3 multi-draw indirect: the commands read by the GPU instead of walked by the driver (-Dlab.chunkmd.noIndirect).
     * Which is faster depends on the machine: an Intel N100's terrain view 83 fps indirect, 120 direct, vanilla 88 (11-12);
     * an RTX 4070 SUPER the other way, 3 660 vs 2 040. Until measured on this machine (PathTune, kept by Calibration): not
     * on Intel's graphics (there the indirect draws, with the section index read from the base instance, cost the GPU
     * more than the direct multi-draw saves the CPU), indirect elsewhere; -Dlab.chunkmd.indirect=true/false forces it.
     */
    private static boolean indirect() {
        return indirectOk() && indirectNow;
    }

    /** the shaders are patched for the indirect path (drawn now or maybe after a measurement) */
    private static boolean indirectPatched() {
        return indirectOk() && (indirectNow || pathTune);
    }

    private static boolean indirectOk() {
        Boolean ok = indirectOk;
        if (ok == null) {
            var caps = GL.getCapabilities();
            String forced = System.getProperty("lab.chunkmd.indirect");
            String vendor = String.valueOf(GL11C.glGetString(GL11C.GL_VENDOR));
            boolean intel = vendor.toLowerCase(java.util.Locale.ROOT).contains("intel");
            ok = (caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect) && !Boolean.getBoolean("lab.chunkmd.noIndirect") && !"false".equals(forced);
            String why;
            if (!ok) {
                why = "";
            } else if (forced != null) {
                indirectNow = true;
                why = " (forced)";
            } else {
                String c = Calibration.get("chunks");
                if ("indirect".equals(c) || "direct".equals(c)) {   // measured on this machine before: the single-path shaders
                    indirectNow = "indirect".equals(c);
                    pathChoice = c + " (calibrated earlier: " + Calibration.get("chunks.why") + ")";
                    why = " - " + pathChoice;
                } else {
                    indirectNow = !intel;
                    pathTune = PATH_TUNE && ssbo();
                    why = (intel ? " (Intel graphics: direct multi-draw until measured)" : "") + (pathTune ? " - measured in the game" : "");
                }
            }
            indirectOk = ok;
            System.out.println("[sleep/opt] chunk multidraw: indirect " + (ok && indirectNow) + why);
        }
        return ok;
    }

    /**
     * Indirect or direct by whole frames of the running game (a synthetic probe at start chose wrongly: the real costs
     * are the driver's command walk and the CPU's, 11-12): once the terrain view has run 20 s with no screen open,
     * windows of the starting path and the other in turn, each frames' mean without the slowest tenth; the other path
     * kept only when at least 3 % faster; far slower (1.3x) at its first window: stopped there. Driven by the solid
     * layer's draw (once a frame). Stored for this machine: the next start uses it from the first frame.
     */
    static final class PathTune {
        private static final int WINDOW = 180, SETTLE = 30, ROUNDS = 2;
        private static final long QUIET = 20_000_000_000L, HITCH = 250_000_000L;
        private static final double GAIN = 0.97, FAR = 1.3;
        private static int phase, frames, window;
        private static long last, since;
        private static boolean start;
        private static final double[] START = new double[ROUNDS], OTHER = new double[ROUNDS];
        private static final Samples S = new Samples(WINDOW);

        static void frame() {
            if (!pathTune || phase == 3) return;
            long now = System.nanoTime();
            long dt = last == 0 ? 0 : now - last;
            last = now;
            if (net.minecraft.client.Minecraft.getInstance().screen != null) {
                if (phase == 0) since = 0;
                last = 0;
                return;
            }
            if (dt <= 0 || dt > HITCH) return;
            if (GpuAnim.measuring() || ParallelRender.measuring()) {   // (one measured choice at a time: this window starts over)
                if (phase == 2) {
                    frames = 0;
                    S.clear();
                }
                return;
            }
            switch (phase) {
                case 0 -> {
                    if (since == 0) since = now;
                    if (now - since < QUIET) return;
                    start = indirectNow;
                    window = 0;
                    phase = 1;
                    frames = 0;
                    System.out.println("[sleep/opt] chunk multidraw: measuring indirect / direct");
                }
                case 1 -> {
                    if (++frames < SETTLE) return;
                    phase = 2;
                    frames = 0;
                    S.clear();
                }
                case 2 -> {
                    S.add(dt);
                    if (++frames < WINDOW) return;
                    double t = S.trimmed();
                    if (window % 2 == 0) START[window / 2] = t; else OTHER[window / 2] = t;
                    window++;
                    if (window == 2 && OTHER[0] > START[0] * FAR) {   // far slower: no second round of it
                        decide(START[0], OTHER[0], 1);
                        return;
                    }
                    if (window < 2 * ROUNDS) {
                        indirectNow = window % 2 == 0 ? start : !start;
                        phase = 1;
                        frames = 0;
                        return;
                    }
                    double a = 0, b = 0;
                    for (int r = 0; r < ROUNDS; r++) {
                        a += START[r];
                        b += OTHER[r];
                    }
                    decide(a / ROUNDS, b / ROUNDS, ROUNDS);
                }
                default -> { }
            }
        }

        private static void decide(double startNs, double otherNs, int rounds) {
            boolean other = otherNs < startNs * GAIN;
            indirectNow = other != start;
            double ind = start ? startNs : otherNs, dir = start ? otherNs : startNs;
            pathChoice = String.format(java.util.Locale.ROOT, "%s (a frame %.3f ms indirect, %.3f direct; %d round%s)",
                    indirectNow ? "indirect" : "direct", ind / 1e6, dir / 1e6, rounds, rounds == 1 ? "" : "s");
            System.out.println("[sleep/opt] chunk multidraw: " + pathChoice);
            phase = 3;
            Calibration.put("chunks", indirectNow ? "indirect" : "direct", pathChoice);
        }

        /** Calibration.reset: measured again (only where the shaders read both indices; else at the next start) */
        static void recalibrate() {
            if (!pathTune) {
                pathChoice = "measured at the next start";
                return;
            }
            phase = 0;
            frames = 0;
            window = 0;
            since = 0;
            last = 0;
            pathChoice = "not measured";
        }
    }

    public static void recalibrate() {
        PathTune.recalibrate();
    }

    private static void queue(Arena a, Entry e, net.minecraft.core.BlockPos o, double x, double y, double z, float ox, float oy, float oz,
            RenderType layer, int[] ps) {
            if (!a.listed) {
                a.listed = true;
                a.n = 0;
                a.maxIndices = 0;
                LISTED.add(a);
            }
            if (a.n + 4 > a.drawCap) {
                if (ssbo()) a.ensureDraws(a.n + 4);
                else flush(a, layer, ps);
            }
            // visible face buckets: a face whose normal points away from the camera (or edge-on) would be culled by the
            // GPU anyway; every +X face of the section lies at x >= minX, so with the camera at x <= minX none is visible
            // (a margin of 1/64 block keeps it conservative against float rounding: more faces drawn, never fewer)
            float[] pl = e.plane;
            double rx = x - o.getX(), ry = y - o.getY(), rz = z - o.getZ();
            int vis = 1 << 3;   // other
            if (allFaces) {
                // a shadow pass (orthographic, from the light): an axis face is front-facing when its normal points
                // toward the light - the others the GPU would cull anyway
                vis |= lightFaces;
            } else {
                if (rx < pl[0] + EPS) vis |= 1;
                if (ry < pl[1] + EPS) vis |= 1 << 1;
                if (rz < pl[2] + EPS) vis |= 1 << 2;
                if (rx > pl[4] - EPS) vis |= 1 << 4;
                if (ry > pl[5] - EPS) vis |= 1 << 5;
                if (rz > pl[6] - EPS) vis |= 1 << 6;
            }
            int[] bq = e.bq;
            int runStart = -1, runEnd = -1;
            for (int k = 0; k <= 7; k++) {
                boolean take = k < 7 && (vis & (1 << k)) != 0;
                boolean empty = k < 7 && bq[k + 1] == bq[k];
                if (take && !empty) {
                    if (runStart < 0) runStart = bq[k];
                    runEnd = bq[k + 1];
                } else if (!empty && runStart >= 0) {   // an invisible, non-empty bucket (or the end) closes the run
                    addDraw(a, e.start + runStart * 4, (runEnd - runStart) * 6, ox, oy, oz, e.recipes ? e.vol : -1, e.id);
                    quadsDrawn += runEnd - runStart;
                    runStart = -1;
                }
            }
            quadsTotal += bq[7];
    }

    private static final double EPS = 1.0 / 64;
    /** a shadow pass draws: every face bucket (seen from the light, not the camera), no reuse of the camera's lists */
    public static boolean allFaces;

    /**
     * The shader expression of a draw's index into the offsets: the base instance each indirect command carries
     * (draws can then be filtered and compacted on the GPU - occlusion culling - and each still finds its offset),
     * else the draw ID (the non-indirect fallback, whose commands carry no base instance).
     */
    public static String drawIndex() {
        if (indirectOk() && pathTune) return "max(gl_BaseInstanceARB, gl_DrawIDARB)";   // (direct: the base instance is 0)
        return indirect() && ssbo() ? "gl_BaseInstanceARB" : "gl_DrawIDARB";
    }
    /** -Dlab.chunkmd.sortNear: the camera's sections ordered nearest first (a reused array; experiment) */
    private static final boolean SORT_NEAR = Boolean.getBoolean("lab.chunkmd.sortNear");
    /** -Dlab.chunkmd.sortFar (with sortNear): farthest first - an overdraw probe */
    private static final boolean SORT_FAR = Boolean.getBoolean("lab.chunkmd.sortFar");
    private static Object[] sortBuf = new Object[1024];
    private static double[] sortKey = new double[1024];

    private static Iterable<?> sortedNearFirst(Iterable<?> in, double x, double y, double z) {
        int n = 0;
        for (Object s : in) {
            if (n == sortBuf.length) {
                sortBuf = java.util.Arrays.copyOf(sortBuf, n * 2);
                sortKey = java.util.Arrays.copyOf(sortKey, n * 2);
            }
            var o = chunkOf(s).getOrigin();
            double dx = o.getX() + 8 - x, dy = o.getY() + 8 - y, dz = o.getZ() + 8 - z;
            sortBuf[n] = s;
            sortKey[n] = (SORT_FAR ? -1 : 1) * (dx * dx + dy * dy + dz * dz);
            n++;
        }
        // insertion sort on nearly ordered input
        for (int i = 1; i < n; i++) {
            double k = sortKey[i];
            Object v = sortBuf[i];
            int j = i - 1;
            while (j >= 0 && sortKey[j] > k) {
                sortKey[j + 1] = sortKey[j];
                sortBuf[j + 1] = sortBuf[j];
                j--;
            }
            sortKey[j + 1] = k;
            sortBuf[j + 1] = v;
        }
        return java.util.Arrays.asList(sortBuf).subList(0, n);
    }
    /** the shadow pass's section set (ShadowPass.collect: count and origins hashed) */
    public static long shadowSignature;
    public static long shadowReuses;
    private static final LayerCache[] SCACHE = newCaches();

    /**
     * A shadow pass's draws in their own lists (every arena's draw list swapped), so the camera pass keeps its lists
     * for reuse; endOtherLists swaps back. (LISTED holds nothing across draw calls.)
     */
    public static void beginOtherLists() {
        swapAll();
    }

    public static void endOtherLists() {
        swapAll();
    }

    private static void swapAll() {
        for (Long2ObjectOpenHashMap<Arena> m : ARENAS) for (Arena a : m.values()) a.swapLists();
        for (Long2ObjectOpenHashMap<Arena> m : PACKED_ARENAS) for (Arena a : m.values()) a.swapLists();
    }
    /** allFaces: the axis buckets facing the light (bits as in queue: -X -Y -Z, other, +X +Y +Z) */
    public static int lightFaces = 0x77;

    /** the direction toward the light, world space: the buckets a shadow pass draws */
    public static void lightDirection(float x, float y, float z) {
        final float e = 1e-4f;
        int v = 0;
        if (x < e) v |= 1;
        if (y < e) v |= 1 << 1;
        if (z < e) v |= 1 << 2;
        if (x > -e) v |= 1 << 4;
        if (y > -e) v |= 1 << 5;
        if (z > -e) v |= 1 << 6;
        lightFaces = v;
    }
    private static final boolean SECTION_LISTS = Boolean.getBoolean("lab.opt.SectionListsMixin");

    private static void addDraw(Arena a, int baseVertex, int indices, float ox, float oy, float oz, int vol, int slot) {
        int i = a.n++;
        MemoryUtil.memPutInt(a.slots + 4L * i, slot);
        MemoryUtil.memPutInt(a.counts + 4L * i, indices);
        MemoryUtil.memPutInt(a.bases + 4L * i, baseVertex);
        a.offsets.putFloat(16 * i, ox).putFloat(16 * i + 4, oy).putFloat(16 * i + 8, oz).putFloat(16 * i + 12, vol);
        if (indices > a.maxIndices) a.maxIndices = indices;
    }

    /** Issues the arena's queued draws as one multi-draw. */
    private static void flush(Arena a, RenderType layer, int[] ps) {
        if (a.n == 0) return;
        GL20C.glUniform1i(ps[0], a.packed ? 2 : 1);
        RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        if (a.vaoStale || a.vao == 0) setupVao(a, layer);
        GL30C.glBindVertexArray(a.vao);
        seq.bind(a.maxIndices);   // element buffer of this VAO, large enough
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, ubo());
        a.offsets.limit(16 * a.n).position(0);
        GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0, a.offsets);
        a.offsets.clear();
        GL31C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, UBO_BINDING, ubo());
        if (LightVolumes.ON && ssbo()) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, LightVolumes.BINDING, LightVolumes.buffer());
        GL32C.nglMultiDrawElementsBaseVertex(GL15C.GL_TRIANGLES, a.counts, seq.type().asGLType, a.indices, a.n, a.bases);
        GL20C.glUniform1i(ps[0], 0);
        multiDraws++;
        a.n = 0;
        a.maxIndices = 0;
    }

    private static int ubo, ssboBuf;
    private static Boolean ssbo;

    private static int ssboBuffer() {
        if (ssboBuf == 0) ssboBuf = GL15C.glGenBuffers();
        return ssboBuf;
    }

    /** Offsets in a shader storage buffer (GL 4.3 / ARB_shader_storage_buffer_object; -Dlab.chunkmd.noSsbo for the uniform block). */
    static boolean ssbo() {
        Boolean s = ssbo;
        if (s == null) {
            var caps = GL.getCapabilities();
            ssbo = s = GlLimits.vertexStorageBlocks() > 0 && !Boolean.getBoolean("lab.chunkmd.noSsbo");
            System.out.println("[sleep/opt] chunk multidraw: offsets in a storage buffer " + s);
        }
        return s;
    }

    private static int ubo() {
        if (ubo == 0) {
            ubo = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, ubo);
            GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, 16L * MAX_DRAWS, GL15C.GL_DYNAMIC_DRAW);
        }
        return ubo;
    }

    /** The patched program's flag uniform and block binding, looked up once; null if the program is not patched. */
    private static int[] programState(int program) {
        int[] s = PROGRAM_STATE.get(program);
        if (s == null) {
            int flag = GL20C.glGetUniformLocation(program, "ProbeMultiDraw");
            boolean sb = ssbo();
            int block = sb ? GL43C.glGetProgramResourceIndex(program, GL43C.GL_SHADER_STORAGE_BLOCK, "ProbeChunkOffsets")
                    : GL31C.glGetUniformBlockIndex(program, "ProbeChunkOffsets");
            if (flag < 0 || block == GL31C.GL_INVALID_INDEX) {
                s = new int[0];
            } else {
                if (sb) GL43C.glShaderStorageBlockBinding(program, block, UBO_BINDING);
                else GL31C.glUniformBlockBinding(program, block, UBO_BINDING);
                if (sb && LightVolumes.ON) {
                    int lv = GL43C.glGetProgramResourceIndex(program, GL43C.GL_SHADER_STORAGE_BLOCK, "ProbeLightVolumes");
                    if (lv != GL31C.GL_INVALID_INDEX) GL43C.glShaderStorageBlockBinding(program, lv, LightVolumes.BINDING);
                    if (lv != GL31C.GL_INVALID_INDEX && GL20C.glGetAttribLocation(program, "ProbeRecipe") < 0) LightVolumes.recipesIgnored("a chunk program");
                }
                s = new int[] {flag};
            }
            PROGRAM_STATE.put(program, s);
        }
        return s.length == 0 ? null : s;
    }

    private static void setupVao(Arena a, RenderType layer) {
        if (a.vao == 0) a.vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(a.vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, a.vbo);
        if (a.packed) {
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_UNSIGNED_SHORT, false, PACKED_STRIDE, 0);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, PACKED_STRIDE, 8);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 2, GL11C.GL_UNSIGNED_SHORT, false, PACKED_STRIDE, 12);
            GL20C.glEnableVertexAttribArray(3);
            GL30C.glVertexAttribIPointer(3, 2, GL11C.GL_UNSIGNED_BYTE, PACKED_STRIDE, 6);
        } else {
            layer.format().setupBufferState();
            if (LightVolumes.ON && a.stride == 32) {
                GL20C.glEnableVertexAttribArray(7);
                GL30C.glVertexAttribIPointer(7, 1, GL11C.GL_UNSIGNED_INT, 32, 28);
            }
        }
        a.vaoStale = false;
    }

    // ------------------------------------------------------------------ packing statistics (-Dlab.chunkmd.packStats)

    private static final boolean PACK_STATS = Boolean.getBoolean("lab.chunkmd.packStats");
    private static final int[] SHIFTS = {4, 8, 10, 11, 12, 16, 20, 23};
    private static final long[] posExact = new long[SHIFTS.length], uvExact = new long[SHIFTS.length];
    private static long statVerts, lightByte, alphaFull;
    private static float posMin = Float.MAX_VALUE, posMax = -Float.MAX_VALUE, uvMin = Float.MAX_VALUE, uvMax = -Float.MAX_VALUE;

    /** Which fields of the BLOCK format would survive a narrower encoding bit-exactly (counts per vertex). */
    private static void packStats(ByteBuffer vb, int verts) {
        long base = MemoryUtil.memAddress(vb);
        for (int i = 0; i < verts; i++) {
            long v = base + 32L * i;
            float x = MemoryUtil.memGetFloat(v), y = MemoryUtil.memGetFloat(v + 4), z = MemoryUtil.memGetFloat(v + 8);
            float u = MemoryUtil.memGetFloat(v + 16), w = MemoryUtil.memGetFloat(v + 20);
            posMin = Math.min(posMin, Math.min(x, Math.min(y, z)));
            posMax = Math.max(posMax, Math.max(x, Math.max(y, z)));
            uvMin = Math.min(uvMin, Math.min(u, w));
            uvMax = Math.max(uvMax, Math.max(u, w));
            for (int k = 0; k < SHIFTS.length; k++) {
                float sc = (float) (1 << SHIFTS[k]);
                if (x * sc == (float) Math.rint(x * sc) && y * sc == (float) Math.rint(y * sc) && z * sc == (float) Math.rint(z * sc)) posExact[k]++;
                if (u * sc == (float) Math.rint(u * sc) && w * sc == (float) Math.rint(w * sc)) uvExact[k]++;
            }
            int l = MemoryUtil.memGetInt(v + 24);
            if ((l & 0xFFFF) <= 255 && (l >>> 16) <= 255) lightByte++;
            if ((MemoryUtil.memGetInt(v + 12) >>> 24) == 255) alphaFull++;
            statVerts++;
        }
    }

    private static String packReport() {
        if (!PACK_STATS || statVerts == 0) return "";
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "; pack stats: %d vertices, pos [%s, %s], uv [%s, %s], light fits bytes %.4f %%, alpha 255 %.4f %%, exact at 2^k (pos / uv):",
                statVerts, posMin, posMax, uvMin, uvMax, 100.0 * lightByte / statVerts, 100.0 * alphaFull / statVerts));
        for (int k = 0; k < SHIFTS.length; k++)
            sb.append(String.format(java.util.Locale.ROOT, " %d: %.4f / %.4f %%", SHIFTS[k], 100.0 * posExact[k] / statVerts, 100.0 * uvExact[k] / statVerts));
        return sb.toString();
    }

    public static String report() {
        int arenas = 0;
        long verts = 0;
        long bytes = 0;
        for (var maps : List.of(ARENAS, PACKED_ARENAS))
            for (var m : maps) {
                arenas += m.size();
                for (Arena a : m.values()) {
                    verts += a.capacity;
                    bytes += (long) a.capacity * a.stride;
                }
            }
        return String.format(java.util.Locale.ROOT, "chunk multidraw: reused draws %d, packed quads %.2f %%, patched %s, multi-draw calls %d, sections by multi-draw %d, by vanilla draws %d, skipped %d, quads drawn %.1f %% (face culling %s), uploads %d, arenas %d (%.1f MB), grows %d, vanilla buffers %s",
                drawReuses, 100.0 * quadsPacked / Math.max(1, quadsPacked + quadsFull), PATCHED, multiDraws, sectionsMulti, sectionsVanilla, sectionsSkipped, 100.0 * quadsDrawn / Math.max(1, quadsTotal), FACE_CULL ? "on" : "off",
                uploads, arenas, bytes / 1048576.0, grows,
                KEEP_VANILLA || PATCHED.size() != SUPPORTED.size() ? "filled" : "not filled") + packReport()
                + (TRANSLUCENT ? String.format(java.util.Locale.ROOT, "; translucent: patched %s, multi-draws %d, frames the vanilla way %d, resorts %d",
                        translucentPatched, translucentDraws, translucentFallbacks, resorts) : "");
    }

    /** the camera's kept layer lists dropped (the GPU terrain: the set of drawn sections changed without a new version) */
    public static void invalidateLayerCaches() {
        for (LayerCache c : LCACHE) {
            c.ver = -1;
            c.stable = false;
        }
    }
}
