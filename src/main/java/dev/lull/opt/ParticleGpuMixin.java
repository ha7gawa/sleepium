package dev.lull.opt;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
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

/** exp/particle-gpu (client): ParticleEngine.render's lists of plain particles drawn from records (ParticleGpu). */
@Mixin(ParticleEngine.class)
public abstract class ParticleGpuMixin {
    private static final String RENDER = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void sleep$lightOpen(CallbackInfo ci) {
        dev.lull.optsupport.ParticleLightMemo.window(true);
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void sleep$lightClose(CallbackInfo ci) {
        dev.lull.optsupport.ParticleLightMemo.window(false);
    }

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleRenderType;begin(Lcom/mojang/blaze3d/vertex/Tesselator;Lnet/minecraft/client/renderer/texture/TextureManager;)Lcom/mojang/blaze3d/vertex/BufferBuilder;"))
    private BufferBuilder sleep$begin(ParticleRenderType type, Tesselator tesselator, TextureManager textures) {
        BufferBuilder bb = type.begin(tesselator, textures);
        ParticleGpu.begin(type, bb, ((ParticleEngineAccessor) this).sleep$particles().get(type));
        return bb;
    }

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void sleep$render(Particle particle, VertexConsumer buffer, Camera camera, float partialTicks) {
        if (!ParticleGpu.add(particle, camera, partialTicks)) particle.render(buffer, camera, partialTicks);
    }

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;build()Lcom/mojang/blaze3d/vertex/MeshData;"))
    private MeshData sleep$build(BufferBuilder bb) {
        ParticleGpu.beforeBuild(bb);
        return bb.build();
    }
}
