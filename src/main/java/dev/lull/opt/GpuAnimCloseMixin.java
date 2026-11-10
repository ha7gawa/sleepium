package dev.lull.opt;

import dev.lull.optsupport.GpuAnim;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.opt.GpuAnim: a closed sprite's GPU sheet freed. */
@Mixin(SpriteContents.class)
public abstract class GpuAnimCloseMixin {
    /** inside an atlas's animation tick only interpolated frames still upload (the rest are copied): their upload timed */
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void sleep$uploadIn(int x, int y, int frameX, int frameY, com.mojang.blaze3d.platform.NativeImage[] data, CallbackInfo ci) {
        if (!GpuAnim.ON || GpuAnim.atlas == 0) return;
        if (GpuAnim.ringUpload((SpriteContents) (Object) this, x, y, frameX, frameY, data)) {   // through the mapped ring
            ci.cancel();
            return;
        }
        GpuAnim.uploadT0 = System.nanoTime();
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void sleep$uploadOut(int x, int y, int frameX, int frameY, com.mojang.blaze3d.platform.NativeImage[] data, CallbackInfo ci) {
        if (GpuAnim.ON && GpuAnim.atlas != 0 && GpuAnim.uploadT0 != 0) {
            GpuAnim.interpUploadNs += System.nanoTime() - GpuAnim.uploadT0;
            GpuAnim.interpUploads++;
            GpuAnim.uploadT0 = 0;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void sleep$releaseSheet(CallbackInfo ci) {
        if (GpuAnim.ON) GpuAnim.release((SpriteContents) (Object) this);
    }
}
