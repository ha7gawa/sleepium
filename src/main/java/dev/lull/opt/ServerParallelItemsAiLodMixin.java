package dev.lull.opt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * lab.fast.aiLod=N (prototype, not exact, notes/101 part 5): a mob with no player within 32 blocks picks goals and
 * senses only every N-th tick (spread by id); in between its running goals go on (tickRunningGoals), and navigation,
 * moves, look and jumps tick as usual.
 */
@Mixin(Mob.class)
public abstract class ServerParallelItemsAiLodMixin {
    private boolean sleep$lodSkip() {
        int n = dev.lull.optsupport.SpecTicks.AI_LOD;
        if (n <= 1) return false;
        Mob m = (Mob) (Object) this;
        if ((m.tickCount + m.getId()) % n == 0) return false;
        boolean far = !m.level().hasNearbyAlivePlayer(m.getX(), m.getY(), m.getZ(), 32);
        if (far) dev.lull.optsupport.SpecTicks.aiLodSkips++;
        return far;
    }

    @WrapOperation(method = "serverAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
    private void sleep$lodGoals(GoalSelector sel, Operation<Void> original) {
        if (sleep$lodSkip()) sel.tickRunningGoals(false);
        else original.call(sel);
    }

    @WrapOperation(method = "serverAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/sensing/Sensing;tick()V"))
    private void sleep$lodSensing(Sensing s, Operation<Void> original) {
        if (!sleep$lodSkip()) original.call(s);
    }
}
