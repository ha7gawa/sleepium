package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): a tracked entity's player checks. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class PipeScoutTrackedMixin {
    @Inject(method = "updatePlayers", at = @At("HEAD"))
    private void sleep$u0(java.util.List<net.minecraft.server.level.ServerPlayer> l, CallbackInfo ci) { PipeScout.updStart(); }

    @Inject(method = "updatePlayers", at = @At("RETURN"))
    private void sleep$u1(java.util.List<net.minecraft.server.level.ServerPlayer> l, CallbackInfo ci) { PipeScout.updEnd(); }

    @Inject(method = "updatePlayer", at = @At("HEAD"))
    private void sleep$one(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.updOne(); }
}
