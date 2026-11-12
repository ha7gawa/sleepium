package dev.lull.opt;

import dev.lull.optsupport.GpuAnim;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.opt.GpuAnim: an animated sprite's next frame copied on the GPU from its sheet (GpuAnim) instead of uploaded. */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public abstract class GpuAnimFrameMixin {
    @Shadow @Final SpriteContents this$0;

    @Shadow
    public abstract int getFrameX(int frameIndex);

    @Shadow
    public abstract int getFrameY(int frameIndex);

    @Inject(method = "uploadFrame", at = @At("HEAD"), cancellable = true)
    private void sleep$gpuFrame(int x, int y, int frameIndex, CallbackInfo ci) {
        if (!GpuAnim.on()) return;
        SpriteContents c = this$0;
        if (GpuAnim.copyFrame(c, c.byMipLevel, x, y, getFrameX(frameIndex) * c.width(), getFrameY(frameIndex) * c.height())) ci.cancel();
    }
}
