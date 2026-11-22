package dev.lull.opt;

import dev.lull.optsupport.LightPar;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LightPar (-Dlab.opt.LightPar, notes/84): a server light batch's increase pass on several threads (optsupport/LightPar
 * has the why). Once per batch and engine (block, sky), not per entry: the cancellable inject is off the hot path.
 */
@Mixin(LightEngine.class)
public abstract class LightParMixin implements LightPar.Engine {
    @Shadow @Final private LongArrayFIFOQueue increaseQueue;
    @Shadow @Final protected LightChunkGetter chunkSource;
    @Shadow @Final public LayerLightSectionStorage<?> storage;
    @Unique private final LightPar.Pending[] sleep$pending = new LightPar.Pending[1];

    @Override public LongArrayFIFOQueue sleep$increaseQueue() { return increaseQueue; }
    @Override public LightChunkGetter sleep$chunkSource() { return chunkSource; }

    @Override
    public Long2ObjectOpenHashMap<DataLayer> sleep$updatingMap() {
        return ((LightParMapAccessor) ((LightParStorageAccessor) storage).sleep$updating()).sleep$map();
    }

    @Override
    public void sleep$setLayer(long sec, DataLayer layer) {
        DataLayerStorageMap<?> m = ((LightParStorageAccessor) storage).sleep$updating();
        m.setLayer(sec, layer);
        m.clearCache();
    }

    @Override public LongSet sleep$changedSections() { return ((LightParStorageAccessor) storage).sleep$changed(); }
    @Override public LongSet sleep$affectedSections() { return ((LightParStorageAccessor) storage).sleep$affected(); }

    @Override
    public int sleep$lowestY() {
        return ((LightParSkyMapAccessor) ((LightParStorageAccessor) storage).sleep$updating()).sleep$lowestY();
    }

    @Inject(method = "propagateIncreases", at = @At("HEAD"), cancellable = true)
    private void sleep$par(CallbackInfoReturnable<Integer> cir) {
        // the server's engines only (not the client's, not private ones - except LightCheck's timed relight)
        if (!(chunkSource instanceof ServerChunkCache) && LightPar.forced != Thread.currentThread()) return;
        int n = LightPar.run(this, (Object) this instanceof SkyLightEngine, sleep$pending);
        if (n >= 0) cir.setReturnValue(n);
    }

    @Inject(method = "propagateIncreases", at = @At("RETURN"))
    private void sleep$verify(CallbackInfoReturnable<Integer> cir) {
        LightPar.Pending p = sleep$pending[0];
        if (p == null) return;
        sleep$pending[0] = null;
        LightPar.verify(this, p);
    }
}
