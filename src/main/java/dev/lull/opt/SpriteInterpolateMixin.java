package dev.lull.opt;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lull.optsupport.SpriteMix;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * An interpolated animated texture's frame blended over the images' memory (SpriteMix), the same pixels as vanilla's
 * per-pixel getPixelRGBA / setPixelRGBA loop; an image vanilla's checks would refuse goes the vanilla way (and throws
 * as it does). The sprite classes' members are public through the access transformer. Another mod's mixin here
 * (Embeddium / Sodium have their own fast interpolation) wins: this one yields (MixinYield).
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$InterpolationData")
public abstract class SpriteInterpolateMixin {
    @Shadow @Final private NativeImage[] activeFrame;
    @Shadow @Final SpriteContents this$0;

    /**
     * @author sleep
     * @reason the blend over the images' memory (see the class comment)
     */
    @Overwrite
    public void uploadInterpolatedFrame(int x, int y, SpriteContents.Ticker ticker) {
        SpriteContents.AnimatedTexture anim = ticker.animationInfo;
        List<SpriteContents.FrameInfo> frames = anim.frames;
        SpriteContents.FrameInfo info = frames.get(ticker.frame);
        double d = 1.0D - (double) ticker.subFrame / (double) info.time;
        int i = info.index;
        int j = frames.get((ticker.frame + 1) % frames.size()).index;
        if (i == j) return;
        SpriteContents c = this$0;
        int width = c.width(), height = c.height();
        // GpuAnim: the blend drawn on the GPU from the sprite's sheet (vanilla's rounding through a table), nothing uploaded
        if (dev.lull.optsupport.GpuAnim.on() && dev.lull.optsupport.GpuAnim.interpolate(c, x, y, anim.getFrameX(i) * width, anim.getFrameY(i) * height,
                anim.getFrameX(j) * width, anim.getFrameY(j) * height, d)) {
            if (dev.lull.optsupport.GpuAnim.VERIFY_BLEND && SpriteMix.interpolate(c.byMipLevel, activeFrame, width, height, anim.getFrameX(i), anim.getFrameY(i), anim.getFrameX(j), anim.getFrameY(j), d))
                dev.lull.optsupport.GpuAnim.verifyBlend(activeFrame[0], x, y, width, height);
            return;
        }
        long tuneAt = dev.lull.optsupport.GpuAnim.tuningCpu() ? System.nanoTime() : 0L;   // (GpuAnim measures the CPU way against its blend)
        if (!SpriteMix.interpolate(c.byMipLevel, activeFrame, width, height, anim.getFrameX(i), anim.getFrameY(i), anim.getFrameX(j), anim.getFrameY(j), d)) {
            SpriteMix.slow++;
            for (int k = 0; k < activeFrame.length; k++) {   // vanilla's loop
                int l = width >> k, h = height >> k;
                if (l < 1 || h < 1) continue;
                NativeImage src = c.byMipLevel[k];
                for (int py = 0; py < h; py++) {
                    for (int px = 0; px < l; px++) {
                        int a = src.getPixelRGBA(px + (anim.getFrameX(i) * width >> k), py + (anim.getFrameY(i) * height >> k));
                        int b = src.getPixelRGBA(px + (anim.getFrameX(j) * width >> k), py + (anim.getFrameY(j) * height >> k));
                        activeFrame[k].setPixelRGBA(px, py, a & 0xFF000000 | SpriteMix.mix(d, a >> 16 & 255, b >> 16 & 255) << 16
                                | SpriteMix.mix(d, a >> 8 & 255, b >> 8 & 255) << 8 | SpriteMix.mix(d, a & 255, b & 255));
                    }
                }
            }
        }
        else if (SpriteMix.VERIFY) {   // -Dlab.sprite.verify=true: each pixel against vanilla's getPixelRGBA / mix
            for (int k = 0; k < activeFrame.length; k++) {
                int l = width >> k, h = height >> k;
                if (l < 1 || h < 1) continue;
                NativeImage src = c.byMipLevel[k];
                for (int py = 0; py < h; py++) {
                    for (int px = 0; px < l; px++) {
                        int a = src.getPixelRGBA(px + (anim.getFrameX(i) * width >> k), py + (anim.getFrameY(i) * height >> k));
                        int b = src.getPixelRGBA(px + (anim.getFrameX(j) * width >> k), py + (anim.getFrameY(j) * height >> k));
                        int v = a & 0xFF000000 | SpriteMix.mix(d, a >> 16 & 255, b >> 16 & 255) << 16 | SpriteMix.mix(d, a >> 8 & 255, b >> 8 & 255) << 8 | SpriteMix.mix(d, a & 255, b & 255);
                        SpriteMix.verified++;
                        if (activeFrame[k].getPixelRGBA(px, py) != v) SpriteMix.mismatches++;
                    }
                }
            }
            if (SpriteMix.fast % 400 == 0) System.out.println("[sleep/opt] sprite interpolation: fast " + SpriteMix.fast + ", slow " + SpriteMix.slow
                    + ", verified pixels " + SpriteMix.verified + ", mismatches " + SpriteMix.mismatches);
        }
        c.upload(x, y, 0, 0, activeFrame);
        if (tuneAt != 0L) dev.lull.optsupport.GpuAnim.tuneCpu(System.nanoTime() - tuneAt);
    }
}
