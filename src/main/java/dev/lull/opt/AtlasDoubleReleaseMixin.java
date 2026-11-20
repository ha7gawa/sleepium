package dev.lull.opt;

import dev.lull.optsupport.AtlasDouble;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dlab.atlasDouble: an atlas texture released takes its back copy along. */
@Mixin(AbstractTexture.class)
public abstract class AtlasDoubleReleaseMixin {
    @Inject(method = "releaseId", at = @At("HEAD"))
    private void sleep$release(CallbackInfo ci) {
        if (AtlasDouble.ON) AtlasDouble.released((AbstractTexture) (Object) this);
    }
}
