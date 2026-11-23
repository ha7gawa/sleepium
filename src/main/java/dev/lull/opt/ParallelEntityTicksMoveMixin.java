package dev.lull.opt;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** exp/parallel-entities (client) and ServerParallelItems (server, lab): a run entity's move in (and removal from) the entity storage, deferred until after the run. */
@Mixin(Entity.class)
public abstract class ParallelEntityTicksMoveMixin {
    @Redirect(method = "setPosRaw", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onMove()V"))
    private void sleep$onMove(EntityInLevelCallback cb) {
        if (!dev.lull.optsupport.SpecTicks.deferMove((Entity) (Object) this, cb) && !dev.lull.optsupport.ParallelEntities.deferMove(cb) && !dev.lull.optsupport.ServerParallelItems.deferMove((Entity) (Object) this, cb)) cb.onMove();
    }

    @Redirect(method = "setRemoved", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onRemove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void sleep$onRemove(EntityInLevelCallback cb, Entity.RemovalReason reason) {
        if (!dev.lull.optsupport.SpecTicks.deferRemove(cb, reason) && !dev.lull.optsupport.ParallelEntities.deferRemove(cb, reason) && !dev.lull.optsupport.ServerParallelItems.deferRemove((Entity) (Object) this, cb, reason)) cb.onRemove(reason);
    }
}
