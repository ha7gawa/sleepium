package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lull.optsupport.ParticleGpu;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * exp/particle-gpu (client): ParticleEngine.render's lists of plain particles drawn from records (ParticleGpu).
 * 1.20.1: the Forge overload with the frustum (remap = false; the calls inside it are vanilla's, remapped); the sheet
 * begins into the Tesselator's builder and its end draws.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleGpuMixin {
    private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V";

    @Inject(method = RENDER, at = @At("HEAD"), remap = false)
    private void sleep$lightOpen(CallbackInfo ci) {
        dev.lull.optsupport.ParticleLightMemo.window(true);
    }

    @Inject(method = RENDER, at = @At("RETURN"), remap = false)
    private void sleep$lightClose(CallbackInfo ci) {
        dev.lull.optsupport.ParticleLightMemo.window(false);
    }

    @Redirect(method = RENDER, remap = false, at = @At(value = "INVOKE", remap = true, target = "Lnet/minecraft/client/particle/ParticleRenderType;begin(Lcom/mojang/blaze3d/vertex/BufferBuilder;Lnet/minecraft/client/renderer/texture/TextureManager;)V"))
    private void sleep$begin(ParticleRenderType type, BufferBuilder bb, TextureManager textures) {
        type.begin(bb, textures);
        ParticleGpu.begin(type, ((BufferBuilderAccessor) bb).sleep$building() ? bb : null, ((ParticleEngineAccessor) this).sleep$particles().get(type));
    }

    @Redirect(method = RENDER, remap = false, at = @At(value = "INVOKE", remap = true, target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void sleep$render(Particle particle, VertexConsumer buffer, Camera camera, float partialTicks) {
        if (!ParticleGpu.add(particle, camera, partialTicks)) particle.render(buffer, camera, partialTicks);
    }

    @Redirect(method = RENDER, remap = false, at = @At(value = "INVOKE", remap = true, target = "Lnet/minecraft/client/particle/ParticleRenderType;end(Lcom/mojang/blaze3d/vertex/Tesselator;)V"))
    private void sleep$end(ParticleRenderType type, Tesselator tesselator) {
        ParticleGpu.beforeBuild(tesselator.getBuilder());
        type.end(tesselator);
        ParticleGpu.afterEnd();
    }
}
