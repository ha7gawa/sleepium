package testmods.foreign;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stand-in for another mod's mixin (package outside dev.lull on purpose): a no-op inject into the tracker's per-pair
 * check. Only applied with -Dsleep.foreignTest=true. The engine's TrackFastMixin (an overwrite of the same method) must
 * then give way (MixinYield: "TrackFastMixin not applied").
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ForeignTrackedMixin {
    @Inject(method = "updatePlayer", at = @At("HEAD"))
    private void foreign$updatePlayer(ServerPlayer player, CallbackInfo ci) {
    }
}
