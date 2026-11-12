package dev.lull.optsupport;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

/**
 * The opaque chunk layers drawn per region without per-draw data in the shader (notes/72): the path for drivers
 * without gl_DrawID / storage buffers - Android's GL translators (LTW: GL 3.3 over GLES), where ChunkMultiDraw cannot
 * run and every section's draw (uniform, bind, glDrawElements) went through the translator: the terrain was half of
 * the phone's render thread.
 * <p>
 * A section's vertices are copied into its region's buffer (8x4x8 sections, one buffer per region and layer, a
 * first-fit free list, grown by glCopyBufferSubData) with the positions rebased: the section's offset inside the
 * region added to them. The region is then one draw call: vanilla's ChunkOffset uniform set to the region's origin
 * minus the camera, glMultiDrawElements over one shared quad index buffer whose indices are absolute (no base vertex:
 * LTW merges a glMultiDrawElements into a single draw). Vanilla's shader, unpatched. A position is then local +
 * (section - region) + (region - camera) instead of local + (section - camera): the float sums may round differently
 * by a fraction of a pixel at geometry edges. Sections without an entry (not uploaded since) draw the vanilla way.
 * <p>
 * On where ChunkMultiDraw's flag is on and its own path is not capable; -Dlab.chunkmd.noRegionDraw=true: off;
 * -Dlab.chunkmd.forceRegionDraw=true: on where ChunkMultiDraw could run too (to check it on a desktop).
 */
public final class RegionDraw {
    private RegionDraw() {}

    private static final int RX = 3, RY = 2, RZ = 3;   // 8 x 4 x 8 sections
    private static final int STRIDE = 32;              // DefaultVertexFormat.BLOCK
    private static final int MAX_DRAWS = (1 << RX) * (1 << RY) * (1 << RZ);
    private static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()};

    private static Boolean on;
    public static long stored, released, regions, draws, multiDraws, vanillaSections, grows, runs;
    /** -Dlab.chunkmd.regionMulti=true: a region's runs in one glMultiDrawElements (a desktop driver) instead of a draw per run */
    private static final boolean MULTI = Boolean.getBoolean("lab.chunkmd.regionMulti");

    /** render thread (GL capabilities): this path draws the opaque layers */
    public static boolean on() {
        Boolean o = on;
        if (o != null) return o;
        if (!Boolean.getBoolean("lab.opt.ChunkMultiDrawMixin") || !ChunkMultiDraw.ACTIVE || Boolean.getBoolean("lab.chunkmd.noRegionDraw")) return on = false;
        org.lwjgl.opengl.GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (Throwable t) {
            return false;   // not the render thread: undecided
        }
        boolean gl = caps.OpenGL31 || caps.GL_ARB_copy_buffer && caps.OpenGL30;
        boolean force = Boolean.getBoolean("lab.chunkmd.forceRegionDraw");
        o = gl && (force || !ChunkMultiDraw.capable());
        on = o;
        System.out.println("[sleep/opt] region draw: " + (o ? "on - the opaque layers one draw per region, vanilla's shader (no draw parameters here"
                + (force ? ", forced" : "") + ")" : "off"));
        return o;
    }

    private static int layerIndex(RenderType t) {
        for (int i = 0; i < LAYERS.length; i++) if (LAYERS[i] == t) return i;
        return -1;
    }

    // ------------------------------------------------------------------ regions

    private static final class Region {
        final long key;
        final int layer;
        final float ox, oy, oz;   // the region's origin in blocks (exact in float: multiples of 64 / 128)
        final double dx, dy, dz;
        int vbo, vao, cap;
        final List<int[]> free = new ArrayList<>();   // [start, length] in vertices, sorted, merged
        // this frame's draws
        int n;
        final long[] runs = new long[MAX_DRAWS];   // start << 32 | verts, sorted into contiguous runs at the draw
        final long counts = MemoryUtil.nmemAlloc(4L * MAX_DRAWS);
        final PointerBuffer offsets = PointerBuffer.allocateDirect(MAX_DRAWS);
        boolean listed;

        Region(long key, int layer, int rx, int ry, int rz) {
            this.key = key;
            this.layer = layer;
            dx = (double) rx * (16 << RX);
            dy = (double) ry * (16 << RY);
            dz = (double) rz * (16 << RZ);
            ox = (float) dx;
            oy = (float) dy;
            oz = (float) dz;
        }

        int alloc(int verts) {
            for (int i = 0; i < free.size(); i++) {
                int[] f = free.get(i);
                if (f[1] >= verts) {
                    int start = f[0];
                    f[0] += verts;
                    f[1] -= verts;
                    if (f[1] == 0) free.remove(i);
                    return start;
                }
            }
            return -1;
        }

        void release(int start, int verts) {
            int i = 0;
            while (i < free.size() && free.get(i)[0] < start) i++;
            free.add(i, new int[] {start, verts});
            if (i + 1 < free.size() && start + verts == free.get(i + 1)[0]) {   // merge with the next
                free.get(i)[1] += free.get(i + 1)[1];
                free.remove(i + 1);
            }
            if (i > 0 && free.get(i - 1)[0] + free.get(i - 1)[1] == start) {   // with the previous
                free.get(i - 1)[1] += free.get(i)[1];
                free.remove(i);
            }
        }
    }

    private static final Map<Long, Region>[] REGIONS = newMaps();
    private static final Map<SectionRenderDispatcher.RenderSection, Entry[]> ENTRIES = new IdentityHashMap<>();
    private static final List<Region> LISTED = new ArrayList<>();
    private static final List<SectionRenderDispatcher.RenderSection> LEFT = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static Map<Long, Region>[] newMaps() {
        Map<Long, Region>[] m = new Map[3];
        for (int i = 0; i < 3; i++) m[i] = new HashMap<>();
        return m;
    }

    private static final class Entry {
        Region region;
        int start, verts;
        long origin;
    }

    // ------------------------------------------------------------------ the shared quad index buffer

    private static int ibo, iboQuads;

    /** indices 0,1,2, 2,3,0 per quad, absolute, for at least quads quads (one buffer; VAOs keep its id) */
    private static void ensureIndices(int quads) {
        if (ibo != 0 && quads <= iboQuads) return;
        int n = Math.max(quads, Math.max(iboQuads * 2, 1 << 14));
        ByteBuffer b = MemoryUtil.memAlloc(n * 6 * 4);
        for (int q = 0; q < n; q++) {
            int v = q * 4;
            b.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
        }
        b.flip();
        if (ibo == 0) ibo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, ibo);   // (not the element target: no VAO's binding touched)
        GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, b, GL15C.GL_STATIC_DRAW);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        MemoryUtil.memFree(b);
        iboQuads = n;
    }

    /** the region's VAO: its buffer with BLOCK's attributes, the shared index buffer; the VAO bound before put back */
    private static void setupVao(Region r) {
        int prev = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        if (r.vao == 0) r.vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(r.vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, r.vbo);
        DefaultVertexFormat.BLOCK.setupBufferState();
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GL30C.glBindVertexArray(prev);
    }

    private static void grow(Region r, int verts) {
        // grown by a quarter (doubling held 41.5 MB for 19 regions at distance 6 on the phone), in steps of 4096 vertices
        int newCap = Math.max(r.cap + r.cap / 4, r.cap + verts);
        newCap = Math.max((newCap + 4095) & ~4095, 4096);
        int nb = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, nb);
        GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, (long) newCap * STRIDE, GL15C.GL_DYNAMIC_DRAW);
        if (r.vbo != 0) {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, r.vbo);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, (long) r.cap * STRIDE);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            GL15C.glDeleteBuffers(r.vbo);
        }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        r.release(r.cap, newCap - r.cap);
        r.vbo = nb;
        r.cap = newCap;
        ensureIndices(newCap / 4);
        setupVao(r);
        grows++;
    }

    private static ByteBuffer scratch = MemoryUtil.memAlloc(1 << 20);

    // ------------------------------------------------------------------ upload / release (render thread)

    /** ChunkUploadMixin, at a section layer's upload: true when the region took it (vanilla's buffer stays empty) */
    public static boolean store(SectionRenderDispatcher.RenderSection section, RenderType layer, MeshData mesh) {
        int li = layerIndex(layer);
        if (li < 0) return false;
        release(section, li);
        MeshData.DrawState ds = mesh.drawState();
        int verts = ds.vertexCount();
        if (ds.format() != DefaultVertexFormat.BLOCK || ds.mode() != VertexFormat.Mode.QUADS || mesh.indexBuffer() != null || verts == 0 || verts % 4 != 0)
            return false;
        var o = section.getOrigin();
        int sx = o.getX() >> 4, sy = o.getY() >> 4, sz = o.getZ() >> 4;
        int rx = sx >> RX, ry = sy >> RY, rz = sz >> RZ;
        long key = ((long) (rx & 0x3FFFFF) << 42) | ((long) (rz & 0x3FFFFF) << 20) | (ry & 0xFFFFF);
        Region r = REGIONS[li].get(key);
        if (r == null) {
            REGIONS[li].put(key, r = new Region(key, li, rx, ry, rz));
            regions++;
        }
        int start = r.alloc(verts);
        if (start < 0) {
            grow(r, verts);
            start = r.alloc(verts);
        }
        // the vertices with the section's offset inside the region added to their positions (BLOCK: position at 0)
        ByteBuffer src = mesh.vertexBuffer();
        int bytes = verts * STRIDE;
        if (scratch.capacity() < bytes) {
            MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Integer.highestOneBit(bytes) << 1);
        }
        long s = MemoryUtil.memAddress(src), d = MemoryUtil.memAddress(scratch);
        MemoryUtil.memCopy(s, d, bytes);
        float fx = (float) (o.getX() - (rx << (RX + 4))), fy = (float) (o.getY() - (ry << (RY + 4))), fz = (float) (o.getZ() - (rz << (RZ + 4)));
        for (int v = 0; v < verts; v++) {
            long p = d + (long) v * STRIDE;
            MemoryUtil.memPutFloat(p, MemoryUtil.memGetFloat(p) + fx);
            MemoryUtil.memPutFloat(p + 4, MemoryUtil.memGetFloat(p + 4) + fy);
            MemoryUtil.memPutFloat(p + 8, MemoryUtil.memGetFloat(p + 8) + fz);
        }
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, r.vbo);
        GL15C.nglBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, (long) start * STRIDE, bytes, d);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        Entry[] es = ENTRIES.computeIfAbsent(section, k -> new Entry[3]);
        Entry e = es[li] == null ? (es[li] = new Entry()) : es[li];
        e.region = r;
        e.start = start;
        e.verts = verts;
        e.origin = o.asLong();
        stored++;
        return true;
    }

    /** ChunkMultiDraw.release (a section's reset: origin change, release): every layer's entry */
    public static void release(SectionRenderDispatcher.RenderSection section) {
        if (on == null || !on) return;
        for (int i = 0; i < 3; i++) release(section, i);
        ENTRIES.remove(section);
    }

    private static void release(SectionRenderDispatcher.RenderSection section, int li) {
        Entry[] es = ENTRIES.get(section);
        if (es == null || es[li] == null || es[li].region == null) return;
        Entry e = es[li];
        e.region.release(e.start, e.verts);
        e.region = null;
        released++;
    }

    // ------------------------------------------------------------------ draw (render thread)

    /**
     * ChunkMultiDrawMixin, in place of renderChunkLayer's section loop (shader applied): the visible sections grouped
     * by region, one glMultiDrawElements each with ChunkOffset at the region's origin; sections without a current
     * entry the vanilla way, after. false: not this path's layer (vanilla's loop).
     */
    public static boolean draw(RenderType layer, Iterable<?> visible, double x, double y, double z, ShaderInstance shader) {
        int li = layerIndex(layer);
        var uniform = shader.CHUNK_OFFSET;
        if (li < 0 || uniform == null || ibo == 0) return false;
        LISTED.clear();
        LEFT.clear();
        for (Object info : visible) {
            SectionRenderDispatcher.RenderSection s = (SectionRenderDispatcher.RenderSection) info;
            if (s.getCompiled().isEmpty(layer)) continue;
            Entry[] es = ENTRIES.get(s);
            Entry e = es == null ? null : es[li];
            if (e == null || e.region == null || e.origin != s.getOrigin().asLong()) {
                LEFT.add(s);
                continue;
            }
            Region r = e.region;
            if (!r.listed) {
                r.listed = true;
                r.n = 0;
                LISTED.add(r);
            }
            r.runs[r.n++] = ((long) e.start << 32) | e.verts;
        }
        if (!LISTED.isEmpty()) BufferUploader.invalidate();
        for (int i = 0; i < LISTED.size(); i++) {
            Region r = LISTED.get(i);
            r.listed = false;
            uniform.set((float) (r.dx - x), (float) (r.dy - y), (float) (r.dz - z));
            uniform.upload();
            GL30C.glBindVertexArray(r.vao);
            // the sections adjacent in the buffer merged into runs, each one glDrawElements: a translator's multi-draw copies
            // every range's indices into a new buffer each call (LTW: the phone's terrain 2.7 -> 3.7 ms, the swap 1.4 -> 4.4)
            java.util.Arrays.sort(r.runs, 0, r.n);
            int m = 0;
            long runStart = r.runs[0] >>> 32, runEnd = runStart + (int) r.runs[0];
            for (int k = 1; k <= r.n; k++) {
                long st = k < r.n ? r.runs[k] >>> 32 : -1, vs = k < r.n ? (int) r.runs[k] : 0;
                if (k < r.n && st == runEnd) {
                    runEnd += vs;
                    continue;
                }
                if (MULTI) {
                    MemoryUtil.memPutInt(r.counts + 4L * m, (int) ((runEnd - runStart) / 4 * 6));
                    r.offsets.put(m, runStart / 4 * 6 * 4);
                } else {
                    GL11C.nglDrawElements(GL11C.GL_TRIANGLES, (int) ((runEnd - runStart) / 4 * 6), GL11C.GL_UNSIGNED_INT, runStart / 4 * 6 * 4);
                }
                m++;
                runStart = st;
                runEnd = st + vs;
            }
            if (MULTI) GL14C.nglMultiDrawElements(GL11C.GL_TRIANGLES, r.counts, GL11C.GL_UNSIGNED_INT, r.offsets.address(), m);
            draws += r.n;
            runs += m;
            multiDraws++;
        }
        for (int i = 0; i < LEFT.size(); i++) {   // not in a region (yet): the vanilla way
            SectionRenderDispatcher.RenderSection s = LEFT.get(i);
            var o = s.getOrigin();
            uniform.set((float) ((double) o.getX() - x), (float) ((double) o.getY() - y), (float) ((double) o.getZ() - z));
            uniform.upload();
            VertexBuffer vb = s.getBuffer(layer);
            vb.bind();
            vb.draw();
            vanillaSections++;
        }
        return true;
    }

    public static String report() {
        int n = 0;
        long verts = 0;
        for (Map<Long, Region> m : REGIONS) for (Region r : m.values()) {
            n++;
            verts += r.cap;
        }
        return String.format(java.util.Locale.ROOT, "region draw: %d regions (%.1f MB), stored %d, released %d, grows %d; drawn %d sections in %d region draws as %d runs, %d the vanilla way",
                n, verts * STRIDE / 1048576.0, stored, released, grows, draws, multiDraws, runs, vanillaSections);
    }
}
