package dev.lull.sleepium.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client bench only (-Dsleep.bench): no torch flicker. Vanilla moves it by Math.random() every tick and the
 * lightmap scales block light by it - level 0 included (its table value is not 0) - so every lit pixel of a screenshot
 * varied between two runs of the same code. The method still runs (it is also what marks the lightmap for an update -
 * cancelling it left the lightmap black, 11-25), the flicker is zeroed after it. Both sides of a comparison run with it.
 */
@Mixin(EntityRenderer.class)
public abstract class BenchFlickerMixin {
    private static final boolean BENCH = System.getProperty("sleep.bench") != null;

    @Shadow private float torchFlickerX;
    @Shadow private float torchFlickerDX;

    @Inject(method = "updateTorchFlicker()V", at = @At("TAIL"))
    private void sleep$still(CallbackInfo ci) {
        if (BENCH) {
            torchFlickerX = 0f;
            torchFlickerDX = 0f;
        }
    }
}
