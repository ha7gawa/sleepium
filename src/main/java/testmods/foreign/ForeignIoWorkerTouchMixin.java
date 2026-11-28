package testmods.foreign;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Issue #18 test probe (-Dsleep.test.touchIoWorker): stand-in for FastChunkGen's MixinStorageIoWorker touching
 * IOWorker.loadAsync, which causes ChunkIoWorkerMixin's @Overwrite to yield.
 */
@Mixin(IOWorker.class)
public abstract class ForeignIoWorkerTouchMixin {
    @Inject(method = "loadAsync", at = @At("HEAD"))
    private void sleep$foreignTouch(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {}
}
