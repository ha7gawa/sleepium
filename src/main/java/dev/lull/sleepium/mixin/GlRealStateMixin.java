package dev.lull.sleepium.mixin;

import net.minecraft.client.renderer.SleepGlState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EntityCache: GL's real enables behind GlStateManager's cache (glPopAttrib changes them without the cache knowing). */
@Mixin(targets = "net.minecraft.client.renderer.GlStateManager$BooleanState")
public abstract class GlRealStateMixin {
    @Shadow private boolean currentState;

    @Inject(method = "setState(Z)V", at = @At("HEAD"))
    private void sleep$set(boolean state, CallbackInfo ci) {
        SleepGlState.onSet(this, state, currentState);
    }
}
