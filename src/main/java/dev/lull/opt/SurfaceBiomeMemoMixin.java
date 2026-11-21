package dev.lull.opt;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SurfaceBiomeMemo (lab, -Dlab.opt.SurfaceBiomeMemoMixin, 11-21): a surface rule's biome test remembers its last biome.
 * The test (BiomeConditionSource's condition, a lazy per-block condition) asks the block's biome holder whether its key is
 * in the source's set - a hash probe into an immutable set for every block the tree reaches it at. Its answer depends on
 * the holder alone (a Reference's key is fixed; the predicate is the source's own final set), and consecutive blocks of a
 * column mostly share their biome: the same holder object gives the same answer without the probe. The condition object
 * belongs to one chunk's context (one thread).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition")
public abstract class SurfaceBiomeMemoMixin {
    @Unique private Holder<?> sleep$holder;
    @Unique private boolean sleep$is;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "compute", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;is(Ljava/util/function/Predicate;)Z"))
    private boolean sleep$is(Holder holder, Predicate test) {
        if (holder == sleep$holder) return sleep$is;
        boolean r = holder.is(test);
        sleep$holder = holder;
        sleep$is = r;
        return r;
    }
}
