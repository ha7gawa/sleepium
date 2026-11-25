package dev.lull.sleepium.mixin;

import dev.lull.sleepium.render.PartBatch;
import java.nio.FloatBuffer;
import javax.vecmath.Matrix4f;
import net.minecraftforge.client.ForgeHooksClient;
import org.lwjgl.BufferUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PartBatch's matrix mirror: Forge's item perspective transforms go to GL directly (GL11.glMultMatrix), not through GlStateManager. */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class PartBatchForgeMatrixMixin {
    private static final FloatBuffer SLEEP_BUF = BufferUtils.createFloatBuffer(16);

    @Inject(method = "multiplyCurrentGlMatrix", at = @At("HEAD"))
    private static void sleep$mult(Matrix4f matrix, CallbackInfo ci) {
        SLEEP_BUF.clear();
        float[] t = new float[4];
        for (int i = 0; i < 4; i++) {
            matrix.getColumn(i, t);
            SLEEP_BUF.put(t);
        }
        SLEEP_BUF.flip();
        PartBatch.mult(SLEEP_BUF);
    }
}
