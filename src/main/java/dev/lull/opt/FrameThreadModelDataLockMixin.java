package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread: NeoForge's ModelDataManager is single-threaded by design (its owning thread); with the frame thread two
 * threads pass its owner check (RenderThreadModelDataMixin's owner view) and work on its maps at once - the main thread
 * (requestRefresh as block entities load, ChunkEvent.Unload) and the frame thread (snapshotSectionRegion when sections
 * are compiled, getAt for block entity renderers). Every operation under one lock (short: map work; the audit 11-16,
 * the same pattern as the client biome memo's crash).
 */
@Mixin(targets = "net.neoforged.neoforge.client.model.data.ModelDataManager", remap = false)
public abstract class FrameThreadModelDataLockMixin {
    private static final Object SLEEP$LOCK = new Object();

    @WrapMethod(method = "requestRefresh", require = 0)
    private void sleep$requestRefresh(net.minecraft.world.level.block.entity.BlockEntity be, Operation<Void> original) {
        synchronized (SLEEP$LOCK) {
            original.call(be);
        }
    }

    @WrapMethod(method = "getAt(Lnet/minecraft/core/SectionPos;)Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", require = 0)
    private it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.neoforged.neoforge.client.model.data.ModelData> sleep$getAtSection(net.minecraft.core.SectionPos pos,
            Operation<it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.neoforged.neoforge.client.model.data.ModelData>> original) {
        synchronized (SLEEP$LOCK) {
            // (the unmodifiable view NeoForge returns wraps the live map: a copy leaves the lock with the caller)
            return new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(original.call(pos));
        }
    }

    @WrapMethod(method = "getAt(Lnet/minecraft/core/BlockPos;)Lnet/neoforged/neoforge/client/model/data/ModelData;", require = 0)
    private net.neoforged.neoforge.client.model.data.ModelData sleep$getAtBlock(net.minecraft.core.BlockPos pos,
            Operation<net.neoforged.neoforge.client.model.data.ModelData> original) {
        synchronized (SLEEP$LOCK) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "snapshotSectionRegion", require = 0)
    private it.unimi.dsi.fastutil.longs.Long2ObjectFunction<net.neoforged.neoforge.client.model.data.ModelData> sleep$snapshot(int x0, int y0, int z0, int x1, int y1, int z1,
            Operation<it.unimi.dsi.fastutil.longs.Long2ObjectFunction<net.neoforged.neoforge.client.model.data.ModelData>> original) {
        synchronized (SLEEP$LOCK) {
            return original.call(x0, y0, z0, x1, y1, z1);
        }
    }

    @WrapMethod(method = "onChunkUnload", require = 0)
    private static void sleep$unload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event, Operation<Void> original) {
        synchronized (SLEEP$LOCK) {
            original.call(event);
        }
    }
}
