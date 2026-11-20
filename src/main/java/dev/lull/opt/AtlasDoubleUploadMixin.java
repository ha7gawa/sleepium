package dev.lull.opt;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lull.optsupport.AtlasDouble;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.atlasDouble: vanilla's own sprite uploads during an animation tick (the interpolated frames) are rects to carry over. */
@Mixin(SpriteContents.class)
public abstract class AtlasDoubleUploadMixin {
    @Inject(method = "upload", at = @At("HEAD"))
    private void sleep$record(int x, int y, int frameX, int frameY, NativeImage[] mips, CallbackInfo ci) {
        if (AtlasDouble.ON) {
            SpriteContents c = (SpriteContents) (Object) this;
            AtlasDouble.recordUpload(x, y, c.width(), c.height(), frameX, frameY, mips);
        }
    }
}
