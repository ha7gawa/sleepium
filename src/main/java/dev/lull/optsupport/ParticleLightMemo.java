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

    /** Particle.getLightColor's value per block position in the window (ParticleGpu, for classes that do not override it) */
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap COLOR = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    public static long colorHits, colorMisses;

    static {
        COLOR.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static void window(boolean opening) {
        windowThread = opening ? Thread.currentThread() : null;
        if (!SKY.isEmpty()) SKY.clear();
        if (!BLOCK.isEmpty()) BLOCK.clear();
        if (!COLOR.isEmpty()) COLOR.clear();
    }

    /**
     * Particle.getLightColor for a particle at (x, y, z): BlockPos.containing, hasChunkAt, LevelRenderer.getLightColor - once
     * per position in the window (the level does not change during ParticleEngine.render), so a hit allocates nothing.
     */
    public static int particleLight(ClientLevel level, double x, double y, double z) {
        long key = BlockPos.asLong(net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(y), net.minecraft.util.Mth.floor(z));
        int v = COLOR.get(key);
        if (v != Integer.MIN_VALUE) {
            colorHits++;
            return v;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        v = level.hasChunkAt(pos) ? net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos) : 0;
        COLOR.put(key, v);
        colorMisses++;
        return v;
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
        return String.format(java.util.Locale.ROOT, "particle light: kept %d, asked %d; light per position kept %d, asked %d", hits, misses, colorHits, colorMisses);
    }
}
