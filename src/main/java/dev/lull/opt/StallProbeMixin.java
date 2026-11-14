package dev.lull.opt;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** StallProbe (-Dlab.stallProbe): a frame begins. */
@Mixin(Minecraft.class)
public abstract class StallProbeMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void sleep$frameStart(boolean renderLevel, CallbackInfo ci) {
        dev.lull.optsupport.StallProbe.frameStart();
    }
}
