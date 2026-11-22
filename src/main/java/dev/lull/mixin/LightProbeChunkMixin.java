package dev.lull.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Measurement only (-Dlab.light.history, notes/84): the block-light engine asked for a chunk it could not get - vanilla
 * then reads BEDROCK there (opaque: light does not enter). Recorded once per chunk and batch.
 */
@Mixin(LightEngine.class)
public abstract class LightProbeChunkMixin {
    @Unique private long sleep$lastNull = Long.MIN_VALUE;
    @Unique private int sleep$lastBatch = -1;

    @Inject(method = "getChunk", at = @At("RETURN"))
    private void sleep$chunk(int x, int z, CallbackInfoReturnable<LightChunk> cir) {
        if (!dev.lull.LightHistory.ON || cir.getReturnValue() != null || !((Object) this instanceof BlockLightEngine)) return;
        long key = SectionPos.asLong(x, 0, z);
        if (key == sleep$lastNull && sleep$lastBatch == dev.lull.LightHistory.batch) return;
        sleep$lastNull = key;
        sleep$lastBatch = dev.lull.LightHistory.batch;
        dev.lull.LightHistory.rec(dev.lull.LightHistory.NULL_CHUNK, key, 0, 0, 0);
    }
}
