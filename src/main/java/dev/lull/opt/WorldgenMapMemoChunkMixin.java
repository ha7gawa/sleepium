package dev.lull.opt;

import dev.lull.optsupport.MapAllMemo;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * lab.opt.WorldgenMapMemoMixin (part): MapAllMemo's session ends with the NoiseChunk's constructor and with each
 * climate sampler it maps, so the memo never keeps a finished chunk's wrappers alive (WorldgenColumnMemoMixin's own
 * sampler ends it too).
 */
@Mixin(NoiseChunk.class)
public abstract class WorldgenMapMemoChunkMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void sleep$endMemo(CallbackInfo ci) {
        MapAllMemo.end();
    }

    @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
    private void sleep$endMemoSampler(CallbackInfoReturnable<?> cir) {
        MapAllMemo.end();
    }
}
