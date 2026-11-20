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
            sleep$t0 = System.nanoTime();   // (11-20: before the double atlas's catch-up copies - they were left out of the tick time)
            GpuAnim.atlas = ((AbstractTexture) (Object) this).getId();
            if (GpuAnim.SCRATCH) GpuAnim.atlas = GpuAnim.scratchFor(GpuAnim.atlas);
            else if (dev.lull.optsupport.AtlasDouble.use()) GpuAnim.atlas = dev.lull.optsupport.AtlasDouble.begin((AbstractTexture) (Object) this, GpuAnim.atlas);
            GpuAnim.tickBegin();
        }
    }

    /** -Dlab.gpuAnim.scratch: vanilla's own uploads (interpolated frames) go to the bound texture - the scratch one */
    @Inject(method = "cycleAnimationFrames", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlas;bind()V", shift = At.Shift.AFTER))
    private void sleep$scratchBind(CallbackInfo ci) {
        if ((GpuAnim.SCRATCH || dev.lull.optsupport.AtlasDouble.ON) && GpuAnim.atlas != 0) com.mojang.blaze3d.platform.GlStateManager._bindTexture(GpuAnim.atlas);
    }

    /** -Dlab.atlasDouble: a rebuilt atlas (resource reload) - its copies are made again from the new content */
    @Inject(method = "upload", at = @At("RETURN"))
    private void sleep$atlasUploaded(CallbackInfo ci) {
        if (dev.lull.optsupport.AtlasDouble.ON) dev.lull.optsupport.AtlasDouble.uploaded((AbstractTexture) (Object) this);
    }

    private static long sleep$t0;

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void sleep$atlasOut(CallbackInfo ci) {
        long ns = -1;
        if (GpuAnim.on() && GpuAnim.atlas != 0) {
            GpuAnim.tickEnd();
            ns = System.nanoTime() - sleep$t0;
            GpuAnim.tickDone(ns);
        }
        if (dev.lull.optsupport.AtlasDouble.ON && GpuAnim.atlas != 0) {   // swap: the atlas draws the back copy from now on
            AtlasIdAccessor acc = (AtlasIdAccessor) (Object) this;
            acc.sleep$setId(dev.lull.optsupport.AtlasDouble.end((AbstractTexture) (Object) this, acc.sleep$id()));
            // the measured choice after the swap (giving the copies up frees the back ones: this tick's is the front now)
            if (ns >= 0) dev.lull.optsupport.AtlasDouble.tickDone(ns);
        }
        GpuAnim.atlas = 0;
    }
}
