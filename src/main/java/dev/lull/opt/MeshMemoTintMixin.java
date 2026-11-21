package dev.lull.opt;

import net.minecraft.client.color.block.BlockTintCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dlab.meshMemo: a tint cache invalidated - for a chunk: its 3 x 3 columns stamped (MeshMemo.tintCleared), all: the
 * global TINT_EPOCH - before and after, so a compile that ran across it (and may have read old colours) keeps no memo.
 */
@Mixin(BlockTintCache.class)
public abstract class MeshMemoTintMixin {
    @Inject(method = "invalidateForChunk", at = @At("HEAD"))
    private void sleep$tintBefore(int x, int z, CallbackInfo ci) {
        dev.lull.optsupport.MeshMemo.tintCleared(x, z);
    }

    @Inject(method = "invalidateForChunk", at = @At("RETURN"))
    private void sleep$tintAfter(int x, int z, CallbackInfo ci) {
        dev.lull.optsupport.MeshMemo.tintCleared(x, z);
    }

    @Inject(method = "invalidateAll", at = @At("HEAD"))
    private void sleep$tintAllBefore(CallbackInfo ci) {
        dev.lull.optsupport.MeshMemo.TINT_EPOCH.incrementAndGet();
    }

    @Inject(method = "invalidateAll", at = @At("RETURN"))
    private void sleep$tintAllAfter(CallbackInfo ci) {
        dev.lull.optsupport.MeshMemo.TINT_EPOCH.incrementAndGet();
    }
}
