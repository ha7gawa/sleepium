package dev.lull.opt;

import dev.lull.optsupport.TrackDeliver;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** lab.opt.TrackDeliver: the tracker's sends are logged while ChunkMap.tick() runs and delivered at its end. */
@Mixin(ChunkMap.class)
public abstract class TrackDeliverChunkMapMixin {
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void sleep$start(CallbackInfo ci) {
        TrackDeliver.start();
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void sleep$finish(CallbackInfo ci) {
        TrackDeliver.finish();
    }
}
