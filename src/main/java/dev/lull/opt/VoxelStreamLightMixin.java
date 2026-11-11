package dev.lull.opt;

import dev.lull.optsupport.VoxelStream;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prototype (lab.voxel.stream): a light change uploads the section's light only, its blocks on the GPU stay. */
@Mixin(net.minecraft.client.multiplayer.ClientChunkCache.class)
public abstract class VoxelStreamLightMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void sleep$voxelLight(LightLayer type, SectionPos pos, CallbackInfo ci) {
        VoxelStream.lightBegin();
    }

    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void sleep$voxelLightEnd(LightLayer type, SectionPos pos, CallbackInfo ci) {
        VoxelStream.causeEnd();
    }
}
