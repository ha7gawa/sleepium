package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): a player's own tick. */
@Mixin(net.minecraft.server.level.ServerPlayer.class)
public abstract class PipeScoutPlayerMixin {
    @Inject(method = "doTick", at = @At("HEAD"))
    private void sleep$d0(CallbackInfo ci) { PipeScout.doStart(); }

    @Inject(method = "doTick", at = @At("RETURN"))
    private void sleep$d1(CallbackInfo ci) { PipeScout.doEnd(); }
}
