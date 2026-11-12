package dev.lull.opt;

import dev.lull.optsupport.GpuAnim;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.opt.GpuAnim: which atlas the sprites' frame copies go into (the one ticking its animations); an atlas with
 *  nothing animated skips its tick (vanilla binds it and walks an empty list, ~12 atlases every client tick). */
@Mixin(TextureAtlas.class)
public abstract class GpuAnimAtlasMixin {
    @Shadow
    private java.util.List<?> animatedTextures;

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void sleep$atlasIn(CallbackInfo ci) {
        if (GpuAnim.on() && animatedTextures.isEmpty()) {   // nothing animated in this atlas: no bind, no loop
            GpuAnim.emptyAtlasTicks++;
            ci.cancel();
            return;
        }
        if (GpuAnim.on()) {
            GpuAnim.atlas = ((AbstractTexture) (Object) this).getId();
            sleep$t0 = System.nanoTime();
            GpuAnim.tickBegin();
        }
    }

    private static long sleep$t0;

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void sleep$atlasOut(CallbackInfo ci) {
        if (GpuAnim.on() && GpuAnim.atlas != 0) {
            GpuAnim.tickEnd();
            GpuAnim.tickDone(System.nanoTime() - sleep$t0);
        }
        GpuAnim.atlas = 0;
    }
}
