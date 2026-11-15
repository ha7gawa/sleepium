package dev.lull.opt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.lull.optsupport.HalfClouds;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dlab.clouds.half (lab.opt.HalfCloudsMixin): the clouds at half resolution (HalfClouds), without Fabulous. */
@Mixin(LevelRenderer.class)
public abstract class HalfCloudsMixin {
    @Shadow private net.minecraft.client.renderer.PostChain transparencyChain;

    @Inject(method = "renderClouds", at = @At("HEAD"))
    private void sleep$halfBegin(CallbackInfo ci) {
        if (transparencyChain == null) HalfClouds.begin();
    }

    @Inject(method = "renderClouds", at = @At("RETURN"))
    private void sleep$halfEnd(CallbackInfo ci) {
        HalfClouds.end();
    }

    @Inject(method = "getCloudsTarget", at = @At("HEAD"), cancellable = true)
    private void sleep$halfTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget t = HalfClouds.target();
        if (t != null) cir.setReturnValue(t);
    }
}
