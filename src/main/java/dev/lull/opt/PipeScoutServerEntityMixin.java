package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): an entity's changes sent, its spawn packets. */
@Mixin(net.minecraft.server.level.ServerEntity.class)
public abstract class PipeScoutServerEntityMixin {
    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void sleep$s0(CallbackInfo ci) { PipeScout.changesStart(); }

    @Inject(method = "sendChanges", at = @At("RETURN"))
    private void sleep$s1(CallbackInfo ci) { PipeScout.changesEnd(); }

    @Inject(method = "addPairing", at = @At("HEAD"))
    private void sleep$p0(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.pairStart(); }

    @Inject(method = "addPairing", at = @At("RETURN"))
    private void sleep$p1(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.pairEnd(); }
}
