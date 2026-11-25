package dev.lull.sleepium.mixin;

import dev.lull.sleepium.server.IntCachePools;
import net.minecraft.world.gen.layer.IntCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** IntCachePools: IntCache's pools per thread (vanilla's code per thread; the main thread's arrays unchanged). */
@Mixin(IntCache.class)
public abstract class IntCacheThreadMixin {
    /**
     * @author Sleepium
     * @reason one set of pools per thread, so world generation can query biomes off the server thread
     */
    @Overwrite
    public static int[] getIntCache(int size) {
        return IntCachePools.get().getIntCache(size);
    }

    /**
     * @author Sleepium
     * @reason one set of pools per thread
     */
    @Overwrite
    public static void resetIntCache() {
        IntCachePools.get().resetIntCache();
    }

    /**
     * @author Sleepium
     * @reason one set of pools per thread
     */
    @Overwrite
    public static String getCacheSizes() {
        return IntCachePools.get().getCacheSizes();
    }
}
