package dev.lull.opt;

import dev.lull.optsupport.VoxelStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prototype (lab.voxel.stream): a section the GPU meshes gets its compiled state from VoxelStream instead of a CPU mesh. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class VoxelStreamCompileMixin {
    @Inject(method = "rebuildSectionAsync", at = @At("HEAD"), cancellable = true)
    private void sleep$voxelAsync(CallbackInfo ci) {
        if (VoxelStream.takeOver(this)) ci.cancel();
    }

    @Inject(method = "compileSync", at = @At("HEAD"), cancellable = true)
    private void sleep$voxelSync(CallbackInfo ci) {
        if (VoxelStream.takeOver(this)) ci.cancel();
    }
}
