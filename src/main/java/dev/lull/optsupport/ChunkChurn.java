package dev.lull.optsupport;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * -Dlab.chunkChurn (measuring, 11-17): what flying costs the meshing workers. In the measured window: chunks received,
 * chunks the server told the client to forget, received ones that had been forgotten before (sent again - the same
 * data, meshed again), and sections meshed. The zoo orbit at distance 32 kept 8 workers on SectionCompiler while a
 * static camera kept them idle.
 */
public final class ChunkChurn {
    private ChunkChurn() {}

    public static final boolean ON = Boolean.getBoolean("lab.chunkChurn");
    private static long received, dropped, resent, resentWithin30s;
    private static final AtomicLong COMPILED = new AtomicLong();
    /** chunk -> when it was forgotten (main thread only) */
    private static final Long2LongOpenHashMap FORGOTTEN = new Long2LongOpenHashMap();

    private static boolean window() {
        return dev.lull.ClientMeter.measuring();
    }

    public static void received(int x, int z) {
        long k = net.minecraft.world.level.ChunkPos.asLong(x, z);
        long at = FORGOTTEN.remove(k);
        if (!window()) return;
        received++;
        if (at != 0) {
            resent++;
            if (System.nanoTime() - at < 30_000_000_000L) resentWithin30s++;
        }
    }

    public static void dropped(int x, int z) {
        FORGOTTEN.put(net.minecraft.world.level.ChunkPos.asLong(x, z), System.nanoTime());
        if (window()) dropped++;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> LAST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong SAME = new AtomicLong(), HASH_NANOS = new AtomicLong();

    /** a section meshed: its inputs' hash against its last mesh's (the 18^3 block states and both lights) */
    public static void compiled(net.minecraft.core.SectionPos pos, net.minecraft.client.renderer.chunk.RenderChunkRegion region) {
        long t = System.nanoTime();
        long h = inputs(pos, region);
        HASH_NANOS.addAndGet(System.nanoTime() - t);
        Long prev = LAST.put(pos.asLong(), h);
        if (!window()) return;
        COMPILED.incrementAndGet();
        if (prev != null && prev == h) SAME.incrementAndGet();
    }

    static long inputs(net.minecraft.core.SectionPos pos, net.minecraft.client.renderer.chunk.RenderChunkRegion region) {
        var m = new net.minecraft.core.BlockPos.MutableBlockPos();
        var light = region.getLightEngine();
        var bl = light.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        var sl = light.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        long h = 0x9E3779B97F4A7C15L;
        int x0 = pos.minBlockX() - 1, y0 = pos.minBlockY() - 1, z0 = pos.minBlockZ() - 1;
        for (int y = 0; y < 18; y++)
            for (int z = 0; z < 18; z++)
                for (int x = 0; x < 18; x++) {
                    m.set(x0 + x, y0 + y, z0 + z);
                    long v = System.identityHashCode(region.getBlockState(m)) * 31L + bl.getLightValue(m) * 17 + sl.getLightValue(m);
                    h = (h ^ v) * 0x100000001B3L;
                    h ^= h >>> 29;
                }
        return h;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "chunk churn (window): received %d, forgotten %d, received again %d (within 30 s %d), sections meshed %d (inputs as at their last mesh %d; hashing %.0f us a section)",
                received, dropped, resent, resentWithin30s, COMPILED.get(), SAME.get(), HASH_NANOS.get() / 1e3 / Math.max(1, LAST.size()));
    }
}
