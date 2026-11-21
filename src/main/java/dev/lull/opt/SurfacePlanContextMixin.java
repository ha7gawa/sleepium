package dev.lull.opt;

import dev.lull.optsupport.SurfacePlan;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** SurfacePlan: the context's per-column min surface level and its generation context, for the root's plan. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public abstract class SurfacePlanContextMixin implements SurfacePlan.ContextAccess {
    @Shadow @Final WorldGenerationContext context;

    @Shadow
    protected abstract int getMinSurfaceLevel();

    @Override
    public int sleep$minSurfaceLevel() {
        return this.getMinSurfaceLevel();
    }

    @Override
    public WorldGenerationContext sleep$genContext() {
        return this.context;
    }
}
