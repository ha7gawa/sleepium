package dev.lull.opt;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LightDirect (notes/84): after the main thread promotes the chunk map (new holders become visible to the light engine),
 * light tasks LightLanes held for not-yet-visible chunks may run. Every call counts (a promotion with nothing changed is
 * a no-op there too).
 */
@Mixin(ChunkMap.class)
public abstract class LightDirectPromoteMixin {
    @Inject(method = "promoteChunkMap", at = @At("RETURN"))
    private void sleep$promoted(CallbackInfoReturnable<Boolean> cir) {
        dev.lull.optsupport.LightLanes.promoted(this);
    }
}
