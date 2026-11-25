package dev.lull.sleepium.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client bench only (-Dsleep.bench): the fog brightness held at 1. Vanilla eases it toward its target by 0.1 every
 * tick in floats - it can settle into alternating between two values - and the frame interpolates it by the fraction of
 * a tick, so the sky and fog of a screenshot came in two variants (1 LSB) between runs of the same code. Both sides of
 * a comparison run with it.
 */
@Mixin(EntityRenderer.class)
public abstract class BenchFogMixin {
    private static final boolean BENCH = System.getProperty("sleep.bench") != null;

    @Shadow private float fogColor1;
    @Shadow private float fogColor2;

    @Inject(method = "updateRenderer()V", at = @At("TAIL"))
    private void sleep$still(CallbackInfo ci) {
        if (BENCH) {
            fogColor1 = 1.0F;
            fogColor2 = 1.0F;
        }
    }
}
