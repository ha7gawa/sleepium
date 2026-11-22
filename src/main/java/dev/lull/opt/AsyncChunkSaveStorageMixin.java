package dev.lull.opt;

import dev.lull.optsupport.AsyncChunkSave;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.levelgen.structure.LegacyStructureDataHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AsyncChunkSaveMixin: reads wait for a pending save of their position; flush and close wait for all of them. */
@Mixin(ChunkStorage.class)
public abstract class AsyncChunkSaveStorageMixin implements AsyncChunkSave.StorageAccess {
    @Shadow @Final private IOWorker worker;
    @Shadow private volatile LegacyStructureDataHandler legacyStructureHandler;

    @Override
    public Object sleep$legacyHandler() {
        return legacyStructureHandler;
    }

    @Override
    public CompletableFuture<Optional<CompoundTag>> sleep$loadDirect(ChunkPos pos) {
        return worker.loadAsync(pos);
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void sleep$read(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        cir.setReturnValue(AsyncChunkSave.read((ChunkStorage) (Object) this, pos));
    }

    @Inject(method = "flushWorker", at = @At("HEAD"))
    private void sleep$flush(CallbackInfo ci) {
        AsyncChunkSave.drain();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void sleep$close(CallbackInfo ci) {
        AsyncChunkSave.drain();
        System.out.println("[sleep/opt] " + AsyncChunkSave.report());
        if (dev.lull.optsupport.EagerSaveScan.walks.get() > 0) System.out.println("[sleep/opt] " + dev.lull.optsupport.EagerSaveScan.report());
        if (dev.lull.optsupport.ParallelBeSave.forks.get() > 0) System.out.println("[sleep/opt] " + dev.lull.optsupport.ParallelBeSave.report());
        if (dev.lull.optsupport.SaveStats.ON) System.out.println("[sleep/opt] " + dev.lull.optsupport.SaveStats.report());
        if (dev.lull.optsupport.SavePalettes.ON) dev.lull.optsupport.SavePalettes.clear();   // the next server's holders are other objects
    }
}
