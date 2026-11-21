package dev.lull.opt;

import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * lab.opt.NoiseWrapFnMixin: NoiseChunk.wrap passes {@code this::wrapNew} to computeIfAbsent, a new bound method
 * reference on every call (computeIfAbsent is too large to inline, so it is not scalar-replaced) - one per node of
 * every traversal. The same function, made once per NoiseChunk; the same map, key and mapping function.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseWrapFnMixin {
    @Shadow @Final private Map<DensityFunction, DensityFunction> wrapped;

    @Shadow
    private DensityFunction wrapNew(DensityFunction densityFunction) {
        throw new AssertionError();
    }

    @Unique private final Function<DensityFunction, DensityFunction> sleep$wrapNew = this::wrapNew;

    /**
     * @author probe
     * @reason the mapping function made once per NoiseChunk
     */
    @Overwrite
    protected DensityFunction wrap(DensityFunction densityFunction) {
        return this.wrapped.computeIfAbsent(densityFunction, this.sleep$wrapNew);
    }
}
