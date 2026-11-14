package dev.lull.optsupport;

import org.lwjgl.opengl.GL15C;

/**
 * -Dlab.gpu.growOnly=true (research, the N100's 360-460 ms swaps every ~2 s in the horde scene): a per-frame buffer
 * keeps one size - its largest so far, rounded up to a power of two, at least 64 KiB - and each frame is orphaned at
 * that size and filled with glBufferSubData, instead of glBufferData at the frame's exact size. The driver then sees
 * the same allocation every frame (a ring of the same blocks), not a new size each time a wave of mobs changes the
 * amount. Render thread only.
 */
public final class GrowBuffer {
    private GrowBuffer() {}

    public static final boolean ON = Boolean.getBoolean("lab.gpu.growOnly");
    private static final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap CAP = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    public static long grows, uploads;

    /** the bound buffer `id` at `target` gets `bytes` from `ptr` (STREAM_DRAW) */
    public static void upload(int target, int id, long bytes, long ptr) {
        if (UploadRing.ON) {
            // the storage made once at a grown size and never specified again; the frame's bytes copied in on the GPU.
            // The size asked of the buffer itself: GL names are reused after a delete (a table by name went stale)
            long cap = org.lwjgl.opengl.GL32C.glGetBufferParameteri64(target, GL15C.GL_BUFFER_SIZE);
            if (bytes > cap) {
                cap = Math.max(65536L, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
                GL15C.nglBufferData(target, cap, 0L, GL15C.GL_DYNAMIC_DRAW);
                grows++;
            }
            if (bytes > 0 && !UploadRing.copy(id, 0, ptr, bytes)) GL15C.nglBufferSubData(target, 0, bytes, ptr);
            uploads++;
            return;
        }
        if (!ON) {
            GL15C.nglBufferData(target, bytes, ptr, GL15C.GL_STREAM_DRAW);
            return;
        }
        long cap = CAP.get(id);
        if (bytes > cap) {
            cap = Math.max(65536L, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
            CAP.put(id, cap);
            grows++;
        }
        GL15C.nglBufferData(target, cap, 0L, GL15C.GL_STREAM_DRAW);   // orphan at the same size
        if (bytes > 0) GL15C.nglBufferSubData(target, 0, bytes, ptr);
        uploads++;
    }
}
