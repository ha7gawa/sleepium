package dev.lull.opt;

import dev.lull.optsupport.GlInbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GlInbox: the texture animations taken out of the client tick, run after the frame is shown. */
@Mixin(Minecraft.class)
public abstract class GlInboxMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void sleep$deferTextures(TextureManager tm) {
        GlInbox.textureTick();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void sleep$afterFrame(boolean renderLevel, CallbackInfo ci) {
        GlInbox.frameEnd((Minecraft) (Object) this);
    }
}
