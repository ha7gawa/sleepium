package dev.lull.opt;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.lull.optsupport.TexParamCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lab (11-20, -Dlab.opt.TexParamCacheMixin=true): glTexParameteri skipped when the bound texture already has the value
 * ({@link TexParamCache}); the bound texture per unit is followed through GlStateManager's own bind / active calls.
 */
@Mixin(GlStateManager.class)
public abstract class TexParamCacheMixin {
    @Inject(method = "_activeTexture", at = @At("HEAD"), remap = false)
    private static void sleep$unit(int texture, CallbackInfo ci) {
        TexParamCache.activeTexture(texture);
    }

    @Inject(method = "_bindTexture", at = @At("HEAD"), remap = false)
    private static void sleep$bind(int texture, CallbackInfo ci) {
        TexParamCache.bound(texture);
    }

    @Inject(method = "_texParameter(III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sleep$param(int target, int pname, int param, CallbackInfo ci) {
        if (TexParamCache.same(target, pname, param)) ci.cancel();
    }

    @Inject(method = "_texParameter(IIF)V", at = @At("HEAD"), remap = false)
    private static void sleep$paramF(int target, int pname, float param, CallbackInfo ci) {
        TexParamCache.changed(target, pname);
    }

    @Inject(method = "_deleteTexture", at = @At("HEAD"), remap = false)
    private static void sleep$delete(int texture, CallbackInfo ci) {
        TexParamCache.deleted(texture);
    }

    @Inject(method = "_deleteTextures", at = @At("HEAD"), remap = false)
    private static void sleep$deleteAll(int[] textures, CallbackInfo ci) {
        for (int t : textures) TexParamCache.deleted(t);
    }
}
