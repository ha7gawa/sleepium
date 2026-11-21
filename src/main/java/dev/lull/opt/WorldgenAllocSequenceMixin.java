package dev.lull.opt;

import java.util.List;
import java.util.RandomAccess;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * WorldgenAlloc: SurfaceRules.SequenceRule.tryApply runs per block and rule level; its for-each allocates an
 * ImmutableList iterator each time (escape analysis loses it in the recursive, megamorphic rule tree): ~16 % of
 * worldgen allocation. Indexed loop over the same list, same order.
 *
 * <p>SurfacePlan (lab.opt.SurfacePlanMixin): the root sequence of a chunk's surface may carry a plan that decides its
 * first tests from the block's height (null without that flag).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
public abstract class WorldgenAllocSequenceMixin implements dev.lull.optsupport.SurfacePlan.Holder {
    @Shadow @Final private List<Object> rules;
    @Unique private dev.lull.optsupport.SurfacePlan sleep$plan;

    @Override
    public void sleep$plan(dev.lull.optsupport.SurfacePlan plan) {
        sleep$plan = plan;
    }

    @Override
    public int sleep$size() {
        return rules.size();
    }

    @Override
    public dev.lull.optsupport.SurfacePlan sleep$planOrNull() {
        return sleep$plan;
    }

    /**
     * @author probe
     * @reason indexed loop, no iterator per call
     */
    @Overwrite
    @Nullable
    public BlockState tryApply(int x, int y, int z) {
        List<Object> l = rules;
        int start = 0;
        dev.lull.optsupport.SurfacePlan p = sleep$plan;
        if (p != null) {
            start = p.eval(y);
            if (start == dev.lull.optsupport.SurfacePlan.NULL) return null;
            if (start == dev.lull.optsupport.SurfacePlan.STATE) return p.state();
        }
        if (dev.lull.optsupport.SurfaceWriter.DIRECT && l instanceof RandomAccess) {
            for (int i = start, n = l.size(); i < n; i++) {
                BlockState s = ((net.minecraft.world.level.levelgen.SurfaceRules.SurfaceRule) l.get(i)).tryApply(x, y, z);
                if (s != null) return s;
            }
            return null;
        }
        if (l instanceof RandomAccess) {
            for (int i = start, n = l.size(); i < n; i++) {
                BlockState s = ((SurfaceRuleInvoker) l.get(i)).sleep$tryApply(x, y, z);
                if (s != null) return s;
            }
            return null;
        }
        for (Object r : l) {
            if (start-- > 0) continue;
            BlockState s = ((SurfaceRuleInvoker) r).sleep$tryApply(x, y, z);
            if (s != null) return s;
        }
        return null;
    }
}
