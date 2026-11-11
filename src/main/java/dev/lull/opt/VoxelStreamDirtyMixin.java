package dev.lull.opt;

import dev.lull.optsupport.VoxelStream;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prototype (lab.voxel.stream): every section vanilla would mesh again (blocks, light, chunk loads) - the GPU mesher's dirty list. */
@Mixin(LevelRenderer.class)
public abstract class VoxelStreamDirtyMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void sleep$voxelDirty(int x, int y, int z, boolean reRenderOnMainThread, CallbackInfo ci) {
        VoxelStream.dirty(x, y, z);
    }
}
