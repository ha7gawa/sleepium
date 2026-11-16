package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameThread (1.20.1): Forge's ModelDataManager keeps plain maps; with the frame thread two threads work on them at once -
 * the main thread (requestRefresh as block entities load, ChunkEvent.Unload) and the frame thread (getAt when sections
 * are compiled and block entities drawn). Every operation under one lock (short: map work; port of 1.21.1's).
 */
@Mixin(targets = "net.minecraftforge.client.model.data.ModelDataManager", remap = false)
public abstract class FrameThreadModelDataLockMixin {
    private static final Object SLEEP$LOCK = new Object();

    @WrapMethod(method = "requestRefresh", require = 0)
    private void sleep$requestRefresh(net.minecraft.world.level.block.entity.BlockEntity be, Operation<Void> original) {
        synchronized (SLEEP$LOCK) {
            original.call(be);
        }
    }

    @WrapMethod(method = "getAt(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/Map;", require = 0)
    private java.util.Map<net.minecraft.core.BlockPos, net.minecraftforge.client.model.data.ModelData> sleep$getAtChunk(net.minecraft.world.level.ChunkPos pos,
            Operation<java.util.Map<net.minecraft.core.BlockPos, net.minecraftforge.client.model.data.ModelData>> original) {
        synchronized (SLEEP$LOCK) {
            // (the map Forge returns is the live one: a copy leaves the lock with the caller)
            return new java.util.HashMap<>(original.call(pos));
        }
    }

    @WrapMethod(method = "getAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraftforge/client/model/data/ModelData;", require = 0)
    private net.minecraftforge.client.model.data.ModelData sleep$getAtBlock(net.minecraft.core.BlockPos pos,
            Operation<net.minecraftforge.client.model.data.ModelData> original) {
        synchronized (SLEEP$LOCK) {
            return original.call(pos);
        }
    }

    @WrapMethod(method = "onChunkUnload", require = 0)
    private static void sleep$unload(net.minecraftforge.event.level.ChunkEvent.Unload event, Operation<Void> original) {
        synchronized (SLEEP$LOCK) {
            original.call(event);
        }
    }
}
