package dev.lull.optsupport;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/** ParticleLightMemoMixin's window (ParticleEngine.render, the render thread) and its memo: a brightness per position and layer. */
public final class ParticleLightMemo {
    private ParticleLightMemo() {}

    /** test switch (ClientAuto's ab) */
    public static volatile boolean enabled = true;
    private static Thread windowThread;
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap SKY = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(),
            BLOCK = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    public static long hits, misses;

    static {
        SKY.defaultReturnValue(-1);
        BLOCK.defaultReturnValue(-1);
    }

    public static void window(boolean opening) {
        windowThread = opening ? Thread.currentThread() : null;
        if (!SKY.isEmpty()) SKY.clear();
        if (!BLOCK.isEmpty()) BLOCK.clear();
    }

    public static boolean open() {
        return enabled && windowThread != null && Thread.currentThread() == windowThread;
    }

    public static int get(ClientLevel level, LightLayer layer, BlockPos pos) {
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap m = layer == LightLayer.SKY ? SKY : BLOCK;
        long key = pos.asLong();
        int v = m.get(key);
        if (v >= 0) {
            hits++;
            return v;
        }
        v = level.getLightEngine().getLayerListener(layer).getLightValue(pos);
        m.put(key, v);
        misses++;
        return v;
    }

    public static String report() {
        return String.format(java.util.Locale.ROOT, "particle light: kept %d, asked %d", hits, misses);
    }
}
