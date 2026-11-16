package dev.lull.opt;

import dev.lull.optsupport.FrameThread;
import java.util.Map;
import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dlab.opt.FrameThreadMixin with lab.frameThread.overlap: a frame drawn while the main thread ticks walks the particle
 * queues as they were when the frame was handed over (FrameThread's snapshot) - the tick adds and removes meanwhile.
 */
@Mixin(ParticleEngine.class)
public abstract class FrameThreadParticlesMixin {
    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V", remap = false,   // (Forge's overload with the frustum)
            at = @At(value = "FIELD", remap = true, target = "Lnet/minecraft/client/particle/ParticleEngine;particles:Ljava/util/Map;", opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private Map<ParticleRenderType, Queue<Particle>> sleep$particlesHeld(ParticleEngine engine) {
        Map<ParticleRenderType, Queue<Particle>> held = FrameThread.heldParticles();
        return held != null ? held : ((ParticleEngineAccessor) engine).sleep$particles();
    }
}
