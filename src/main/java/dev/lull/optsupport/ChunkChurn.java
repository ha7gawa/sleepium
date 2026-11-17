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

    public static void compiled() {
        if (window()) COMPILED.incrementAndGet();
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "chunk churn (window): received %d, forgotten %d, received again %d (within 30 s %d), sections meshed %d",
                received, dropped, resent, resentWithin30s, COMPILED.get());
    }
}
