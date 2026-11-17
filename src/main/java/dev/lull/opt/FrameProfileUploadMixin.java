package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.lull.optsupport.FrameProfile;
import dev.lull.optsupport.FrameThread;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * FrameProfile (measuring): a vertex buffer's upload ("upload") and its draw ("draw") as sections where they are -
 * wrapped whole (other mixins cancel upload at its head: a HEAD / RETURN pair leaked the profiler's stack)
 */
@Mixin(VertexBuffer.class)
public abstract class FrameProfileUploadMixin {
    @WrapMethod(method = "upload")
    private void sleep$upload(BufferBuilder.RenderedBuffer mesh, Operation<Void> original) {
        if (!FrameThread.drawingHere()) {
            original.call(mesh);
            return;
        }
        FrameProfile.INSTANCE.push("upload");
        try {
            original.call(mesh);
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }

    @WrapMethod(method = "drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V")
    private void sleep$draw(Matrix4f view, Matrix4f proj, ShaderInstance shader, Operation<Void> original) {
        if (!FrameThread.drawingHere()) {
            original.call(view, proj, shader);
            return;
        }
        FrameProfile.INSTANCE.push("draw");
        try {
            original.call(view, proj, shader);
        } finally {
            FrameProfile.INSTANCE.pop();
        }
    }
}
