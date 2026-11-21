package dev.lull.opt;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.lull.optsupport.NoiseOnce;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * lab.opt.WorldgenNoiseOnceMixin: a NoiseChunk builds its density functions from the router with the extra cache marks
 * of NoiseOnce (repeated noise subtrees once per corner, subtrees free of y once per column). Exact: see NoiseOnce.
 */
@Mixin(NoiseChunk.class)
public abstract class WorldgenNoiseOnceMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter sleep$marked(NoiseRouter r) {
        return NoiseOnce.router(r);
    }
}
