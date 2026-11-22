package dev.lull.opt;

import dev.lull.optsupport.PipeScout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (lab.pipescout): the entity tracker's tick and the per-player chunk tracking. */
@Mixin(net.minecraft.server.level.ChunkMap.class)
public abstract class PipeScoutChunkMapMixin {
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void sleep$t0(CallbackInfo ci) { PipeScout.trackerStart(); }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void sleep$t1(CallbackInfo ci) { PipeScout.trackerEnd(); }

    @Inject(method = "move", at = @At("HEAD"))
    private void sleep$m0(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.cmoveStart(); }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getLastSectionPos()Lnet/minecraft/core/SectionPos;"))
    private void sleep$mLoop(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.cmoveLoopEnd(); }

    @Inject(method = "move", at = @At("RETURN"))
    private void sleep$m1(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.cmoveEnd(); }

    @Inject(method = "updateChunkTracking", at = @At("HEAD"))
    private void sleep$c0(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.trackStart(); }

    @Inject(method = "updateChunkTracking", at = @At("RETURN"))
    private void sleep$c1(net.minecraft.server.level.ServerPlayer p, CallbackInfo ci) { PipeScout.trackEnd(); }
}
