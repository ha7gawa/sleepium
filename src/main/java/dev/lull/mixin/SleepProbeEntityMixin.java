package dev.lull.mixin;

import dev.lull.optsupport.sleep.SleepEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every entity box change (spawn, move, load, a new size) may move it into or out of a box a sleeper read: one event
 * over the old and the new box together. (The position is not the hook: vanilla sets the box after it, so a test there
 * saw the old box - a boat stopping in a sleeping item's box never woke it, tools/exactness.py vehicles.) Sleep engines
 * only.
 */
@Mixin(Entity.class)
public abstract class SleepProbeEntityMixin {
    @Shadow private AABB bb;
    @Shadow private EntityInLevelCallback levelCallback;

    @Inject(method = "setBoundingBox", at = @At("HEAD"))
    private void sleep$boxed(AABB to, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // Not in a level yet (the constructor's setPos, a spawn before addFreshEntity): no box query sees the entity, so
        // its old box is the plain field. getBoundingBox() is a mod's to override and may read fields its constructor has
        // not set yet - vanilla never calls it here (issue #12: Bosses of Mass Destruction's Void Blossom, built by
        // Hostile Neural Networks' item renderer in FTB Quests' screen, crashed with a null hitbox helper).
        SleepEngine.onMove(self, levelCallback == EntityInLevelCallback.NULL ? bb : self.getBoundingBox(), to);
    }
}
