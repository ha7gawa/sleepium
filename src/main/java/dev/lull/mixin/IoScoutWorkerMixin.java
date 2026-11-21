package dev.lull.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.IoScout;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Measurement only (-Dsleep.ioScout=true): the IOWorker queue: stores posted, loads, each chunk written and the backlog. */
@Mixin(IOWorker.class)
public abstract class IoScoutWorkerMixin {
    @Shadow @Final private Map<ChunkPos, ?> pendingWrites;
    @Shadow public abstract RegionStorageInfo storageInfo();

    @WrapMethod(method = "store")
    private java.util.concurrent.CompletableFuture<Void> sleep$store(ChunkPos pos, net.minecraft.nbt.CompoundTag data, Operation<java.util.concurrent.CompletableFuture<Void>> original) {
        RegionStorageInfo info = storageInfo();
        IoScout.stored(info.type());
        if (data == null) IoScout.nullStore(info.type());
        if (IoScout.POS) {
            String status = data == null ? "null" : data.contains("Status") ? data.getString("Status") : "-";
            IoScout.storePos(info.dimension().location().getPath(), info.type(), pos.toLong(), status);
            if (!"chunk".equals(info.type())) IoScout.storer(info.type() + (data == null ? " null" : ""), IoScout.caller("store"));
        }
        return original.call(pos, data);
    }

    @WrapMethod(method = "loadAsync")
    private java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>> sleep$load(ChunkPos pos,
            Operation<java.util.concurrent.CompletableFuture<java.util.Optional<net.minecraft.nbt.CompoundTag>>> original) {
        RegionStorageInfo info = storageInfo();
        IoScout.loaded(info.type());
        if (IoScout.POS) {
            IoScout.loadPos(info.dimension().location().getPath(), info.type(), pos.toLong());
            if ("chunk".equals(info.type())) IoScout.loader(IoScout.caller("loadAsync"));
        }
        return original.call(pos);
    }

    // PendingStore is private: plain injects without the arguments (runStore catches everything, so RETURN always runs)
    @org.spongepowered.asm.mixin.injection.Inject(method = "runStore", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void sleep$runStoreStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        IoScout.beginWrite(storageInfo().type());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "runStore", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void sleep$runStoreEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        IoScout.endWrite(pendingWrites.size());
    }

    /** the writer's thread names its storage type for RegionBitmap.allocate (ChunkIo's writer has no runStore) */
    @org.spongepowered.asm.mixin.injection.Inject(method = "storePendingChunk", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void sleep$writerStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        IoScout.writerBegin(storageInfo().type());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "storePendingChunk", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void sleep$writerEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        IoScout.writerEnd();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "close", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void sleep$closed(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ("chunk".equals(storageInfo().type())) IoScout.closing();
    }
}
