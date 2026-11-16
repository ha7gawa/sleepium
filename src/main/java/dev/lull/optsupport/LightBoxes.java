package dev.lull.optsupport;

import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

/**
 * The level's light in boxes the GPU reads (PartBatch mode 6: SuperByteBuffers lit by the level - Create's contraptions
 * and their actors, which catnip lights per vertex on the CPU every frame). A box covers where a part's vertices fall,
 * with a margin so a moving contraption keeps it a while; one byte per block (block light | sky light << 4) as
 * LevelRenderer.getLightColor gives it (the block's own emission included).
 *
 * <p>A new box is filled before its first draw. A change of blocks or light in a section it touches (RenderSection
 * setDirty, LightVolumes.lightChanged) refills it on the frame's workers; until that lands the box keeps its previous
 * light (as vanilla keeps a section's old mesh until the re-mesh). Boxes not asked for a while are freed.
 *
 * <p>Layout of the storage buffer: MAX headers of 8 uints (origin xyz, size xyz, byte offset of the cells, 0), then
 * the cells, y-major then z then x.
 */
public final class LightBoxes {
    private LightBoxes() {}

    public static final int BINDING = 4;
    private static final int MAX = 1024, HEADER_BYTES = MAX * 32, MARGIN = 4;
    /** a box larger than this (cells) is not made: the part keeps the CPU path */
    private static final int MAX_CELLS = Integer.getInteger("lab.lightboxes.maxCells", 1 << 21);
    private static final int IDLE_FRAMES = 240;

    static final class Box {
        int idx, ox, oy, oz, sx, sy, sz, off, bytes, seq;
        long lastUsed;
        boolean dirty, pending;
    }

    private static final ArrayList<Box> LIVE = new ArrayList<>();
    private static final int[] FREE_IDX = new int[MAX];
    private static int freeIdxN, nextIdx;
    /** free cell ranges (offset, bytes), first fit */
    private static final ArrayList<int[]> FREE = new ArrayList<>();
    private static int buffer, capacity, top = HEADER_BYTES;
    private static long frame;
    private static Object level;
    public static long made, fills, asyncFills, freed, tooBig;

    public static String GLSL = """
            layout(std430) buffer ProbeBoxes { uint pb[]; };
            ivec2 probeBoxLight(int box, ivec3 p) {
                int h = box * 8;
                ivec3 c = p - ivec3(int(pb[h]), int(pb[h + 1]), int(pb[h + 2]));
                ivec3 sz = ivec3(int(pb[h + 3]), int(pb[h + 4]), int(pb[h + 5]));
                if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, sz))) return ivec2(0);
                int idx = int(pb[h + 6]) + (c.y * sz.z + c.z) * sz.x + c.x;
                uint b = (pb[idx >> 2] >> uint((idx & 3) * 8)) & 255u;
                return ivec2(int(b & 15u) << 4, int(b >> 4u) << 4);
            }
            """;

    public static int buffer() {
        if (buffer == 0) grow(HEADER_BYTES + (1 << 20));
        return buffer;
    }

    private static void grow(int bytes) {
        int nb = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, nb);
        GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER, bytes, GL15C.GL_DYNAMIC_DRAW);
        if (buffer != 0) {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, capacity);
            GL15C.glDeleteBuffers(buffer);
        }
        buffer = nb;
        capacity = bytes;
    }

    /** A box index whose box holds the blocks (x0..x1, y0..y1, z0..z1) of this level, or -1. */
    public static int boxFor(BlockAndTintGetter lv, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (lv != Minecraft.getInstance().level) return -1;
        for (int i = 0, n = LIVE.size(); i < n; i++) {
            Box b = LIVE.get(i);
            if (x0 >= b.ox && y0 >= b.oy && z0 >= b.oz && x1 < b.ox + b.sx && y1 < b.oy + b.sy && z1 < b.oz + b.sz) {
                b.lastUsed = frame;
                return b.idx;
            }
        }
        long cells = (long) (x1 - x0 + 1 + 2 * MARGIN) * (y1 - y0 + 1 + 2 * MARGIN) * (z1 - z0 + 1 + 2 * MARGIN);
        if (cells > MAX_CELLS || freeIdxN == 0 && nextIdx >= MAX) {
            tooBig++;
            return -1;
        }
        buffer();
        Box b = new Box();
        b.idx = freeIdxN > 0 ? FREE_IDX[--freeIdxN] : nextIdx++;
        b.ox = x0 - MARGIN;
        b.oy = y0 - MARGIN;
        b.oz = z0 - MARGIN;
        b.sx = x1 - x0 + 1 + 2 * MARGIN;
        b.sy = y1 - y0 + 1 + 2 * MARGIN;
        b.sz = z1 - z0 + 1 + 2 * MARGIN;
        b.bytes = (int) ((cells + 3) & ~3L);
        b.off = alloc(b.bytes);
        b.lastUsed = frame;
        LIVE.add(b);
        long mem = MemoryUtil.nmemAlloc(b.bytes);
        compute(lv, b, mem);
        upload(b, mem);
        MemoryUtil.nmemFree(mem);
        made++;
        return b.idx;
    }

    private static int alloc(int bytes) {
        for (int i = 0; i < FREE.size(); i++) {
            int[] f = FREE.get(i);
            if (f[1] >= bytes) {
                int off = f[0];
                f[0] += bytes;
                f[1] -= bytes;
                if (f[1] == 0) FREE.remove(i);
                return off;
            }
        }
        int off = top;
        top += bytes;
        if (top > capacity) grow(Math.max(capacity * 2, top + (1 << 20)));
        return off;
    }

    private static void compute(BlockAndTintGetter lv, Box b, long mem) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = 0; y < b.sy; y++)
            for (int z = 0; z < b.sz; z++)
                for (int x = 0; x < b.sx; x++) {
                    m.set(b.ox + x, b.oy + y, b.oz + z);
                    int lc = LevelRenderer.getLightColor(lv, lv.getBlockState(m), m);
                    MemoryUtil.memPutByte(mem + i++, (byte) ((lc >> 4 & 15) | (lc >> 20 & 15) << 4));
                }
        fills++;
    }

    private static void upload(Box b, long mem) {
        long h = MemoryUtil.nmemAlloc(32);
        MemoryUtil.memPutInt(h, b.ox);
        MemoryUtil.memPutInt(h + 4, b.oy);
        MemoryUtil.memPutInt(h + 8, b.oz);
        MemoryUtil.memPutInt(h + 12, b.sx);
        MemoryUtil.memPutInt(h + 16, b.sy);
        MemoryUtil.memPutInt(h + 20, b.sz);
        MemoryUtil.memPutInt(h + 24, b.off);
        MemoryUtil.memPutInt(h + 28, 0);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15C.nglBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 32L * b.idx, 32, h);
        GL15C.nglBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, b.off, b.bytes, mem);
        MemoryUtil.nmemFree(h);
    }

    /** Blocks or light of section (x, y, z) changed: the boxes touching it are refilled. */
    public static void sectionChanged(int x, int y, int z) {
        if (LIVE.isEmpty()) return;
        int bx = x << 4, by = y << 4, bz = z << 4;
        for (int i = 0, n = LIVE.size(); i < n; i++) {
            Box b = LIVE.get(i);
            if (bx + 16 > b.ox && bx < b.ox + b.sx && by + 16 > b.oy && by < b.oy + b.sy && bz + 16 > b.oz && bz < b.oz + b.sz) b.dirty = true;
        }
    }

    /** Start of renderLevel: a new level drops every box; dirty boxes are refilled on the workers; idle ones freed. */
    public static void frame() {
        frame++;
        FrameReactor.drain();   // finished refills (LightVolumes drains too when it is on)
        Object lv = Minecraft.getInstance().level;
        if (lv != level) {
            level = lv;
            for (int i = LIVE.size() - 1; i >= 0; i--) free(LIVE.get(i));
        }
        if (LIVE.isEmpty() || lv == null) return;
        var cl = Minecraft.getInstance().level;
        for (int i = LIVE.size() - 1; i >= 0; i--) {
            Box b = LIVE.get(i);
            if (frame - b.lastUsed > IDLE_FRAMES) {
                free(b);
                continue;
            }
            if (!b.dirty) continue;
            b.dirty = false;
            int seq = ++b.seq;
            long mem = MemoryUtil.nmemAlloc(b.bytes);
            FrameReactor.pool().execute(() -> {
                compute(cl, b, mem);
                FrameReactor.completeGl(() -> {
                    if (b.seq == seq && LIVE.contains(b)) upload(b, mem);
                    MemoryUtil.nmemFree(mem);
                });
            });
            asyncFills++;
        }
    }

    private static void free(Box b) {
        LIVE.remove(b);
        b.seq++;   // a refill still running is dropped
        FREE_IDX[freeIdxN++] = b.idx;
        FREE.add(new int[] {b.off, b.bytes});
        freed++;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "light boxes: %d live, made %d, fills %d (on workers %d), freed %d, too big %d, %.1f MB",
                LIVE.size(), made, fills, asyncFills, freed, tooBig, capacity / 1048576.0);
    }
}
