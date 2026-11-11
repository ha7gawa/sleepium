package dev.lull.opt;

import dev.lull.optsupport.VoxelLod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement prototypes (lab.voxel.lod, lab.voxel.compute): a frame's start and end, for the per-mode CPU and GPU frame times. */
@Mixin(GameRenderer.class)
public abstract class VoxelLodFrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void sleep$voxelFrameBegin(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VoxelLod.frameBegin();
        dev.lull.optsupport.VoxelCompute.frameBegin();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void sleep$voxelFrameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VoxelLod.frameEnd();
        dev.lull.optsupport.VoxelCompute.frameEnd();
    }
}
