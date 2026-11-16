package dev.lull.opt;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * exp/client-biome-memo (client): the render thread's noise-biome lookups (fog colour: 216 per frame, sky colour, the
 * ambient particles of animateTick: ~1300 per tick) answered from a per-level memo of quart position -> biome holder.
 * The answer is what LevelReader.getNoiseBiome gives for the chunk data at hand; ClientChunkCacheBiomeMixin marks the
 * memo stale whenever that data can change (chunk loaded, updated, dropped, biomes replaced, view centre or radius
 * moved). Other threads (section compiles ask for tints) go the vanilla way.
 * <p>
 * With the frame thread (FrameThread) two threads ask - the main thread's tick (animateTick) and the frame thread's fog
 * and sky - at the same time: each has its own memo, and a change only raises an epoch that each memo checks on its
 * own thread (11-16, Distant Horizons tour: the one shared map was corrupted, a crash in its rehash).
 */
@Mixin(ClientLevel.class)
public abstract class ClientBiomeMemoMixin implements LevelReader, dev.lull.optsupport.BiomeMemo {
    @Unique private final Long2ObjectOpenHashMap<Holder<Biome>> sleep$biomes = new Long2ObjectOpenHashMap<>();
    @Unique private final Long2ObjectOpenHashMap<Holder<Biome>> sleep$biomesFrame = new Long2ObjectOpenHashMap<>();
    @Unique private volatile int sleep$epoch;
    @Unique private int sleep$seen, sleep$seenFrame;
    @Unique private static long sleep$hits, sleep$misses, sleep$clears;

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        Long2ObjectOpenHashMap<Holder<Biome>> memo;
        int e = sleep$epoch;
        if (dev.lull.optsupport.FrameThread.onFrameThread()) {
            memo = sleep$biomesFrame;
            if (sleep$seenFrame != e) {
                memo.clear();
                sleep$seenFrame = e;
            }
        } else if (RenderSystem.isOnRenderThread() || dev.lull.optsupport.FrameThread.onMainThread()) {
            memo = sleep$biomes;
            if (sleep$seen != e) {
                memo.clear();
                sleep$seen = e;
            }
        } else return LevelReader.super.getNoiseBiome(x, y, z);
        long k = BlockPos.asLong(x, y, z);
        Holder<Biome> h = memo.get(k);
        if (h != null) {
            sleep$hits++;
            return h;
        }
        h = LevelReader.super.getNoiseBiome(x, y, z);
        if (memo.size() >= 65536) memo.clear();
        memo.put(k, h);
        sleep$misses++;
        return h;
    }

    @Override
    public void sleep$biomesChanged() {
        sleep$epoch++;   // (each memo clears itself on its own thread at its next lookup)
        sleep$clears++;
    }

    @Override
    public String sleep$biomeReport() {
        return "biome memo: hits " + sleep$hits + ", misses " + sleep$misses + ", clears " + sleep$clears;
    }
}
