package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lull.optsupport.StoredRing;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * StoredRing (see dev.lull.optsupport.StoredRing): a chunk IOWorker keeps early proto chunks' records in RAM; stores,
 * loads and scans of a held position are answered by the ring, synchronize and close write every held record first.
 */
@Mixin(IOWorker.class)
public abstract class StoredRingMixin {
    @Shadow @Final private RegionFileStorage storage;

    @Unique private static final ThreadLocal<Boolean> sleep$bypass = new ThreadLocal<>();
    @Unique private volatile StoredRing sleep$ring;
    @Unique private volatile boolean sleep$checked;
    /** 1.20.1: the worker's name ("chunk", "entities", "poi") and folder (1.21: storage.info()) */
    @Unique private String sleep$name = "?";
    @Unique private String sleep$folder = "?";

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$named(java.nio.file.Path folder, boolean sync, String workerName, CallbackInfo ci) {
        sleep$name = workerName;
        sleep$folder = String.valueOf(folder);
    }

    @Unique
    private StoredRing sleep$ring() {
        if (!sleep$checked) {
            synchronized (this) {
                if (!sleep$checked) {
                    if ("chunk".equals(sleep$name)) {
                        IOWorker self = (IOWorker) (Object) this;
                        sleep$ring = new StoredRing(sleep$folder, (pos, tag) -> {
                            sleep$bypass.set(Boolean.TRUE);
                            try {
                                return self.store(pos, tag);
                            } finally {
                                sleep$bypass.remove();
                            }
                        });
                    }
                    sleep$checked = true;
                }
            }
        }
        return sleep$ring;
    }

    @WrapMethod(method = "store")
    private CompletableFuture<Void> sleep$store(ChunkPos pos, CompoundTag tag, Operation<CompletableFuture<Void>> original) {
        StoredRing ring = sleep$ring();
        if (ring == null || sleep$bypass.get() != null) return original.call(pos, tag);
        CompletableFuture<Void> f = ring.store(pos, tag);
        if (f != null) return f;
        return ring.storeThrough(pos, () -> original.call(pos, tag));
    }

    @WrapMethod(method = "loadAsync")
    private CompletableFuture<Optional<CompoundTag>> sleep$load(ChunkPos pos, Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        StoredRing ring = sleep$ring();
        CompletableFuture<Optional<CompoundTag>> f = ring == null ? null : ring.load(pos);
        return f != null ? f : original.call(pos);
    }

    @WrapMethod(method = "scanChunk")
    private CompletableFuture<Void> sleep$scan(ChunkPos pos, StreamTagVisitor visitor, Operation<CompletableFuture<Void>> original) {
        StoredRing ring = sleep$ring();
        CompletableFuture<Void> f = ring == null ? null : ring.scan(pos, visitor);
        return f != null ? f : original.call(pos, visitor);
    }

    @WrapMethod(method = "synchronize")
    private CompletableFuture<Void> sleep$sync(boolean flush, Operation<CompletableFuture<Void>> original) {
        StoredRing ring = sleep$ring();
        if (ring != null) ring.releaseAll();
        return original.call(flush);
    }

    /** around the whole close: the held records reach the IOWorker before anything of close runs (ChunkIo drains its encodes at its HEAD) */
    @WrapMethod(method = "close")
    private void sleep$close(Operation<Void> original) throws IOException {
        StoredRing ring = sleep$ring();
        if (ring != null) ring.releaseAll();
        original.call();
    }

    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;close()V"))
    private void sleep$verify(CallbackInfo ci) {
        StoredRing ring = sleep$ring();
        if (ring == null) return;
        ring.verifyAll(pos -> {
            try {
                return storage.read(pos);
            } catch (IOException e) {
                return null;
            }
        });
        System.out.println("[sleep/opt] " + StoredRing.report());
    }
}
