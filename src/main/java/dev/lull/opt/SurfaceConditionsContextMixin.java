package dev.lull.opt;

import dev.lull.optsupport.SurfaceConditions;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** SurfaceConditions: a context's table of shared conditions (made with the rule tree, once per chunk). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public abstract class SurfaceConditionsContextMixin implements SurfaceConditions.Table {
    @Unique private Map<Object, Object> sleep$conditions;

    @Override
    public Map<Object, Object> sleep$conditions() {
        Map<Object, Object> m = sleep$conditions;
        if (m == null) sleep$conditions = m = SurfaceConditions.newTable();
        return m;
    }
}
