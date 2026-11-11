package dev.lull.opt;

import dev.lull.optsupport.VoxelLod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement prototypes (lab.voxel.lod, lab.voxel.compute): the ray pass / the GPU-meshed faces after the world, on the pixels the raster left as sky. */
@Mixin(LevelRenderer.class)
public abstract class VoxelLodLevelMixin {
    /** after the terrain layers, before entities and the translucent layer (profiler "entities") */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=entities"))
    private void sleep$voxelTerrain(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        dev.lull.optsupport.VoxelCompute.afterTerrain(frustumMatrix, projectionMatrix);
        dev.lull.optsupport.VoxelStream.afterTerrain(frustumMatrix, projectionMatrix);
    }

    /** before vanilla's translucent layer (profiler "translucent", in both the Fabulous and the other branch) */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=translucent"))
    private void sleep$voxelTranslucent(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        dev.lull.optsupport.VoxelStream.translucent(frustumMatrix, projectionMatrix);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void sleep$voxelRays(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        VoxelLod.afterLevel(frustumMatrix, projectionMatrix);
        dev.lull.optsupport.VoxelCompute.afterLevel(frustumMatrix, projectionMatrix);
    }
}
